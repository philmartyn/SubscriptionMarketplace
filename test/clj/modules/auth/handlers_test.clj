(ns modules.auth.handlers-test
  "The auth HTTP handlers, exercised without a running server.

  Each handler gets the request shape wrap-base delivers: keywordized params, a
  session map, and the components under :system. Assertions here stay on the
  HTTP surface - status, redirect target, htmx headers, session changes - while
  the rules they delegate to are modules.auth.service-test's."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [modules.auth.db :as db]
   [modules.auth.handlers :as handlers]
   [modules.auth.mailer :as mailer]
   [modules.auth.service :as service]
   [modules.auth.support :as sup]))

(use-fixtures :each
  sup/with-db
  sup/with-fast-argon2)

(defn- mailer-for
  "A recording mailer, fresh per test so captured mail never leaks."
  []
  (first (mailer/recording-mailer)))

(defn- request
  "A POST request shaped the way wrap-base hands one to a route handler."
  [path params & [{:keys [htmx? session mailer dev-verify?]}]]
  {:request-method :post
   :uri            path
   :params         params
   :session        (or session {})
   :headers        (when htmx? {"hx-request" "true"})
   :system         {:db     sup/*db*
                    :mailer (or mailer (mailer-for))
                    :config {:base-url    "http://localhost:3000"
                             :dev-verify? dev-verify?}}})

(defn- signup-input
  [overrides]
  (merge {:name     "Ada Lovelace"
          :email    "ada@example.com"
          :password "an-appropriate-password!"}
         overrides))

(defn- count-rows
  [table]
  (:n (jdbc/execute-one! sup/*db*
                         [(str "SELECT count(*) AS n FROM " table)]
                         {:builder-fn rs/as-unqualified-lower-maps})))

;;; signup

(deftest signup-creates-an-account-and-turns-the-browser-to-the-dashboard
  (let [resp (handlers/signup (request "/auth/signup" (signup-input {})))]
    (is (= 303 (:status resp)))
    (is (str/includes? (get-in resp [:headers "Location"]) "/dashboard"))
    (let [id (get-in resp [:session :account-id])]
      (is (some? id) "the session is signed in")
      (let [account (db/find-account-by-id sup/*db* id)]
        (is (= "ada@example.com" (:email account)))
        (is (false? (db/email-verified? account))
            "a fresh account still needs verification")))))

(deftest signup-from-htmx-redirects-via-header-and-still-signs-the-session-in
  (let [resp (handlers/signup
              (request "/auth/signup" (signup-input {}) {:htmx? true}))]
    (is (= 200 (:status resp)))
    (is (= "/dashboard" (get-in resp [:headers "HX-Redirect"])))
    (is (some? (get-in resp [:session :account-id]))
        "the htmx branch signs the session in too, so the redirect is authenticated")))

(deftest signup-shows-errors-and-keeps-the-values
  (let [resp (handlers/signup
              (request "/auth/signup"
                       (signup-input {:email "not-an-email" :password "short"})))
        html (:body resp)]
    (is (= 200 (:status resp)))
    (is (str/includes? html "That does not look like an email address."))
    (is (str/includes? html "Use at least 12 characters."))
    (is (str/includes? html "value=\"not-an-email\"")
        "the submitted email is echoed back so nobody retypes it")
    (is (nil? (get-in resp [:session :account-id])))))

(deftest an-email-that-is-already-registered-fails
  (handlers/signup (request "/auth/signup" (signup-input {})))
  (let [resp (handlers/signup (request "/auth/signup" (signup-input {})))]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "An account already exists for that email."))))

(deftest a-failed-htmx-submit-swaps-in-a-fragment-not-a-page
  (let [resp (handlers/signin
              (request "/auth/signin"
                       {:email "nobody@example.com" :password "whatever"}
                       {:htmx? true}))]
    (is (= 200 (:status resp)))
    (is (not (re-find #"(?i)<!doctype html>" (:body resp)))
        "htmx swaps the form element, so the reply is a fragment, not a document")))

;;; vendor signup

(deftest vendor-signup-creates-a-vendor-and-turns-the-browser-to-its-dashboard
  (let [resp (handlers/vendor-signup
              (request "/auth/vendor-signup" (signup-input {})))]
    (is (= 303 (:status resp)))
    (is (str/includes? (get-in resp [:headers "Location"]) "/vendor/dashboard"))
    (let [id (get-in resp [:session :account-id])
          account (db/find-account-by-id sup/*db* id)]
      (is (= :vendor (service/account-type-of account)))
      (is (= 0 (count-rows "users"))
          "a vendor signs up as a vendor, not a half-subscriber")
      (is (= 1 (count-rows "vendors"))))))

;;; sign in

(deftest signin-with-the-right-password-signs-in
  (handlers/signup (request "/auth/signup" (signup-input {})))
  (let [resp (handlers/signin
              (request "/auth/signin"
                       {:email "ada@example.com"
                        :password "an-appropriate-password!"}))]
    (is (= 303 (:status resp)))
    (is (str/includes? (get-in resp [:headers "Location"]) "/dashboard"))
    (is (some? (get-in resp [:session :account-id])))))

(deftest signin-with-a-vendor-account-turns-the-browser-to-the-vendor-dashboard
  (handlers/vendor-signup (request "/auth/vendor-signup" (signup-input {})))
  (let [resp (handlers/signin
              (request "/auth/signin"
                       {:email "ada@example.com"
                        :password "an-appropriate-password!"}))]
    (is (= 303 (:status resp)))
    (is (str/includes? (get-in resp [:headers "Location"]) "/vendor/dashboard"))))

(deftest signin-with-the-wrong-password-does-not-sign-in
  (handlers/signup (request "/auth/signup" (signup-input {})))
  (let [resp (handlers/signin
              (request "/auth/signin"
                       {:email "ada@example.com"
                        :password "totally-the-wrong-password"}))]
    (is (= 200 (:status resp)))
    (is (nil? (get-in resp [:session :account-id])))
    (is (str/includes? (:body resp) "That email and password do not match."))))

(deftest an-unknown-email-fails-the-same-way-as-a-wrong-password
  (let [resp (handlers/signin
              (request "/auth/signin"
                       {:email "nobody@example.com"
                        :password "an-appropriate-password!"}))]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "That email and password do not match."))))

;;; verification

(defn- token-of
  "The one-time token embedded in a registration result's verification url."
  [{:keys [verification-url]}]
  (second (str/split verification-url #"token=")))

(deftest verify-email-consumes-the-token-and-turns-the-browser-to-the-dashboard
  (let [created (service/register! sup/*db* (mailer-for)
                                   {:base-url "http://localhost:3000"}
                                   {:account-type :user
                                    :email "ada@example.com"
                                    :password "an-appropriate-password!"
                                    :name "Ada"})
        resp (handlers/verify-email
              (request "/auth/verify" {:token (token-of created)}))]
    (is (= 302 (:status resp)))
    (is (str/includes? (get-in resp [:headers "Location"]) "/dashboard"))
    (is (db/email-verified?
         (db/find-account-by-id sup/*db* (:account-id created))))
    (testing "a replayed link fails quietly"
      (let [again (handlers/verify-email
                   (request "/auth/verify" {:token (token-of created)}))]
        (is (= 302 (:status again)))
        (is (str/includes? (get-in again [:headers "Location"])
                           "/auth/signin?verify=invalid"))))))

(deftest verify-email-with-an-invalid-token-points-back-at-sign-in
  (let [resp (handlers/verify-email (request "/auth/verify" {:token "garbage"}))]
    (is (= 302 (:status resp)))
    (is (str/includes? (get-in resp [:headers "Location"])
                       "/auth/signin?verify=invalid"))))

(deftest resend-verification-sends-a-fresh-link
  (let [created (service/register! sup/*db* (mailer-for)
                                   {:base-url "http://localhost:3000"}
                                   {:account-type :user
                                    :email "ada@example.com"
                                    :password "an-appropriate-password!"
                                    :name "Ada"})
        m     (mailer-for)
        resp  (handlers/resend-verification
               (request "/auth/resend-verification" {}
                        {:mailer m
                         :session {:account-id (:account-id created)}}))]
    (is (= 302 (:status resp)))
    (is (str/includes? (get-in resp [:headers "Location"]) "/dashboard"))
    (let [{:keys [to body]} (first @(:messages m))]
      (is (= "ada@example.com" to))
      (is (str/includes? body "/auth/verify?token=")))))

(deftest verified-accounts-can-no-longer-ask-for-a-link
  (let [created (service/register! sup/*db* (mailer-for)
                                   {:base-url "http://localhost:3000"}
                                   {:account-type :user
                                    :email "ada@example.com"
                                    :password "an-appropriate-password!"
                                    :name "Ada"})]
    (service/verify-email sup/*db* (token-of created))
    (let [resp (handlers/resend-verification
                (request "/auth/resend-verification" {}
                         {:session {:account-id (:account-id created)}}))]
      (is (= 302 (:status resp)))
      (is (str/includes? (get-in resp [:headers "Location"]) "/auth/signin")))))

;;; simulate email click (dev only)

(defn- register-unverified!
  "A fresh, unverified subscriber account, as signup would leave it."
  []
  (service/register! sup/*db* (mailer-for)
                     {:base-url "http://localhost:3000"}
                     {:account-type :user
                      :email "ada@example.com"
                      :password "an-appropriate-password!"
                      :name "Ada"}))

(deftest verify-now-marks-the-account-verified-and-turns-to-the-dashboard
  (let [created (register-unverified!)
        acct-id (:account-id created)
        resp    (handlers/verify-now
                 (request "/auth/dev-verify" {}
                          {:dev-verify? true
                           :session {:account-id acct-id}}))]
    (is (= 302 (:status resp)))
    (is (str/includes? (get-in resp [:headers "Location"]) "/dashboard"))
    (is (db/email-verified? (db/find-account-by-id sup/*db* acct-id)))))

(deftest verify-now-from-htmx-redirects-via-header
  (let [created (register-unverified!)
        acct-id (:account-id created)
        resp    (handlers/verify-now
                 (request "/auth/dev-verify" {}
                          {:dev-verify? true :htmx? true
                           :session {:account-id acct-id}}))]
    (is (= 200 (:status resp)))
    (is (= "/dashboard" (get-in resp [:headers "HX-Redirect"])))
    (is (db/email-verified? (db/find-account-by-id sup/*db* acct-id)))))

(deftest verify-now-is-unavailable-outside-development
  (let [created (register-unverified!)
        acct-id (:account-id created)
        resp    (handlers/verify-now
                 (request "/auth/dev-verify" {}
                          {:dev-verify? false
                           :session {:account-id acct-id}}))]
    (is (= 404 (:status resp))
        "the bypass is not advertised where it is not enabled")
    (is (false? (db/email-verified? (db/find-account-by-id sup/*db* acct-id)))
        "and it leaves the account unverified")))

(deftest verify-now-bounces-anonymous-visitors
  (let [resp (handlers/verify-now
              (request "/auth/dev-verify" {} {:dev-verify? true}))]
    (is (= 302 (:status resp)))
    (is (str/includes? (get-in resp [:headers "Location"]) "/auth/signin"))))

(deftest verify-now-is-idempotent
  (let [created (register-unverified!)
        acct-id (:account-id created)
        submit  #(handlers/verify-now
                  (request "/auth/dev-verify" {}
                           {:dev-verify? true
                            :session {:account-id acct-id}}))]
    (is (= 302 (:status (submit))))
    (let [resp (submit)]
      (is (= 302 (:status resp)))
      (is (str/includes? (get-in resp [:headers "Location"]) "/dashboard"))
      (is (db/email-verified? (db/find-account-by-id sup/*db* acct-id))))))

;;; sign out

(deftest signout-clears-the-session-and-the-cookie
  (let [resp (handlers/signout (request "/auth/signout" {}))]
    (is (= 303 (:status resp)))
    (is (= "/" (get-in resp [:headers "Location"])))
    (is (nil? (:session resp))
        "nil session tells Ring to revoke the session server side")
    (is (= {:max-age 0} (:session-cookie-attrs resp))
        "the cookie attrs are what actually remove the browser cookie")))

(deftest signout-from-htmx-redirects-via-header
  (let [resp (handlers/signout (request "/auth/signout" {} {:htmx? true}))]
    (is (= 200 (:status resp)))
    (is (= "/" (get-in resp [:headers "HX-Redirect"])))
    (is (nil? (:session resp)))))