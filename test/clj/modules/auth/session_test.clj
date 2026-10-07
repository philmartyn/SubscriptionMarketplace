(ns modules.auth.session-test
  "Tests for the Postgres-backed session store.

  The property being protected is revocability: a session that was signed out
  must stop working even though a browser may still hold the cookie, and the
  stored value must not be the token itself.

  The other half is fidelity: a Ring session store must give back the session
  map it was given, because something real lives in that map - the anti-forgery
  token. An anonymous visitor's session exists precisely to carry it."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [modules.auth.db :as db]
   [modules.auth.session :as session]
   [modules.auth.support :as sup]
   [next.jdbc :as jdbc]
   [ring.middleware.session :as ring.session]
   [ring.middleware.session.store :as store]))

(use-fixtures :each
  sup/with-db
  sup/with-fast-argon2)

(defn- account!
  "A registered account to hang sessions off."
  ([] (account! "ada@example.com"))
  ([email]
   (db/register! sup/*db*
                 {:email            email
                  :email-normalized email
                  :password-hash    "not-used-by-these-tests"
                  :account-type     :user
                  :name             "Ada"})))

(defn- the-store [] (session/session-store sup/*db*))

(defn- login!
  "Write a session the way a signed-in response would."
  [account-id]
  (store/write-session (the-store) nil {:account-id account-id}))

(defn- session-rows []
  (jdbc/execute! sup/*db* ["select * from sessions order by id"]))

;;; the protocol

(deftest a-new-session-round-trips
  (let [account (account!)
        token (login! (:id account))
        read (store/read-session (the-store) token)]
    (is (string? token) "the key that goes in the cookie is a string")
    (is (= (:id account) (:account-id read)))
    (is (= "ada@example.com" (:email read)))
    (is (= :user (:account-type read)))
    (is (false? (:verified? read)))))

(deftest nothing-sensitive-is-stored-in-the-session-map
  (let [account (account!)
        read (store/read-session
              (the-store) (login! (:id account)))]
    (is (nil? (:password_hash read))
        "the hash must not travel in the session map")
    (is (not (contains? read :password-hash)))))

(deftest the-token-is-never-stored-in-the-clear
  (let [account (account!)
        token (login! (:id account))
        stored (map :token_hash (session-rows))]
    (is (= 1 (count stored)))
    (is (not= token (first stored)) "the raw token is not what lands on disk")
    (is (re-matches #"[0-9a-f]{64}" (first stored))
        "what is stored is a 64-character sha-256 hex digest of the token")))

;;; reading

(deftest nothing-is-read-without-a-key
  (testing "Ring passes nil for a browser with no cookie"
    (is (nil? (store/read-session (the-store) nil))))
  (testing "and a stale or tampered cookie must not reach the database"
    (is (nil? (store/read-session (the-store) "")))
    (is (nil? (store/read-session (the-store) "not-a-token"))))
  (is (empty? (session-rows)) "no session row was created by looking"))

(deftest an-expired-session-stops-being-readable
  (let [account (account!)
        token (login! (:id account))]
    (is (some? (store/read-session (the-store) token)))
    (jdbc/execute! sup/*db* ["update sessions set expires_at = now() - interval '1 day'"])
    (is (nil? (store/read-session (the-store) token))
        "expiry is checked on read, not just at creation")))

;;; writing

(def ^:private csrf-key
  "The key Ring's session strategy stores its synchronizer token under."
  :ring.middleware.anti-forgery/anti-forgery-token)

(def csrf-map
  "The session map an unauthenticated visitor holding a form page is given."
  {csrf-key "the-anti-forgery-token"})

(deftest an-existing-session-keeps-its-token-and-updates-its-data
  (let [token (store/write-session (the-store) nil csrf-map)
        same (store/write-session (the-store) token (assoc csrf-map :notice "hi"))]
    (is (= token same) "the cookie stays stable across requests")
    (is (= 1 (count (session-rows))) "and no second row is opened")
    (is (= "hi" (:notice (store/read-session (the-store) token)))
        "a later write under the same token is a rewrite, not a new session")))

(deftest an-anonymous-session-is-persisted-and-readable
  ;; Ring's anti-forgery middleware writes its token into the session map
  ;; before the visitor has any account. If that row does not exist, the POST
  ;; that follows has nothing to compare the form token against and fails with
  ;; 403 for everyone.
  (let [token (store/write-session (the-store) nil csrf-map)
        read (store/read-session (the-store) token)]
    (is (string? token) "a cookie is issued so the token survives the page")
    (is (= csrf-map read)
        "exactly what was written comes back - the token Ring validates")
    (is (not (contains? read :account-id))
        "an anonymous session is not half-authenticated")
    (is (= 1 (count (session-rows))))))

(deftest signing-in-attaches-the-account-to-the-session
  (let [account (account!)
        token (store/write-session (the-store) nil csrf-map)]
    (store/write-session (the-store) token (assoc csrf-map :account-id (:id account)))
    (let [read (store/read-session (the-store) token)]
      (is (= (:id account) (:account-id read)))
      (is (= :user (:account-type read)))
      (is (= csrf-map
             (select-keys read (keys csrf-map)))
          "the CSRF token survives sign-in on the same row"))
    (is (= 1 (count (session-rows))) "no second session was opened")))

;;; signing out

(deftest each-account-gets-its-own-session
  (let [one (account! "one@example.com")
        two (account! "two@example.com")]
    (is (not= (login! (:id one)) (login! (:id two)))
        "tokens must be unguessable, so they must differ")
    (is (= 2 (count (session-rows))))))

;;; signing out

(deftest signing-out-revokes-the-session
  (let [account (account!)
        token (login! (:id account))]
    (is (some? (store/read-session (the-store) token)))
    (is (nil? (store/delete-session (the-store) token))
        "returning nil is what tells Ring to drop the cookie")
    (is (nil? (store/read-session (the-store) token))
        (str "the whole point of a server side store: the cookie may still be "
             "in the browser, but it no longer authenticates anything"))
    (is (= 1 (count (session-rows))) "the row remains, marked revoked")))

(deftest signing-out-leaves-other-sessions-alone
  (let [account (account!)
        token (login! (:id account))
        other (login! (:id account))]
    (store/delete-session (the-store) token)
    (is (some? (store/read-session (the-store) other))
        "one device signing out must not sign out the rest")))

(deftest signing-out-twice-is-harmless
  (let [account (account!)
        token (login! (:id account))]
    (store/delete-session (the-store) token)
    (is (nil? (store/delete-session (the-store) token))
        "a replayed logout is not an error")
    (is (nil? (store/delete-session (the-store) nil))
        "and neither is one with no cookie at all")))

;;; through Ring

(defn- set-cookie
  "The value Ring set for cookie-name, read back out of the Set-Cookie header.

  ring.middleware.cookies converts a response's :cookies map into headers and
  then removes the map, so the value is no longer where the session middleware
  left it."
  [response cookie-name]
  (let [raw (get-in response [:headers "Set-Cookie"])
        lines (cond
                (string? raw) [raw]
                (sequential? raw) raw
                :else nil)]
    (some (fn [line]
            (second (re-find (re-pattern (str "^" cookie-name "=([^;]*)")) line)))
          lines)))

(defn- set-cookie-header
  "The whole Set-Cookie header as one string.

  Ring allows it to be a single string or a collection once more than one
  cookie is set, so joining is the only safe way to inspect it."
  [response]
  (let [raw (get-in response [:headers "Set-Cookie"])]
    (str/join "\n" (cond
                     (string? raw) [raw]
                     (sequential? raw) raw
                     :else nil))))

(deftest a-signed-in-browser-gets-a-cookie-that-authenticates
  (let [account (account!)
        app (ring.session/wrap-session
             (fn [request]
               (if (= "/login" (:uri request))
                 {:status 200 :body "logged in"
                  :session {:account-id (:id account)}}
                 {:status 200 :body (pr-str (:session request))}))
             {:store (the-store) :cookie-name "sid"})
        login (app {:request-method :post :uri "/login"})
        token (set-cookie login "sid")
        home (app {:request-method :get :uri "/"
                   :cookies {"sid" {:value token}}})]
    (is (some? token) "signing in sets the cookie")
    (is (str/includes? (set-cookie-header login) "HttpOnly")
        "the cookie must not be readable from JavaScript")
    (is (str/includes? (get home :body) (str (:id account)))
        "a later request with that cookie is authenticated")))
