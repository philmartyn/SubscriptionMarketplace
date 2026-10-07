(ns modules.auth.session
  "Ring session storage backed by the sessions table.

  The cookie carries one opaque, unguessable token and nothing else; the session
  itself lives in Postgres. That is what makes sign-out real. The server can
  revoke a session while the browser still holds a cookie that looks valid, and
  a copied cookie stops working the moment it is revoked. A cookie store cannot
  offer that, because the data travels inside the cookie and there is no
  server-side record to invalidate.

  The token is hashed before it is stored, so a database dump yields no usable
  session keys either - the same reasoning as the verification tokens.

  The session map written here holds no secrets: it is derived from the accounts
  row on every read rather than persisted, so what it contains is constrained by
  what is already on disk."
  (:require
   [clojure.string :as str]
   [modules.auth.crypto :as crypto]
   [modules.auth.db :as db]
   [ring.middleware.session.store :as store]))

(def ^:private ttl-days
  "How long a session survives without being renewed."
  30)

(defn- hash-of [token]
  (crypto/token-hash token))

(defn- present?
  "Ring passes nil when the browser has no cookie, and a stale cookie may hold
  an empty or garbage value. Neither should reach the database."
  [token]
  (not (str/blank? (str token))))

(defrecord PostgresSessionStore [database]
  store/SessionStore

  (read-session [_ token]
    (when (present? token)
      (when-let [session (db/find-active-session database (hash-of token))]
        {:account-id   (:account_id session)
         :account-type (keyword (:account_type session))
         :email        (:email session)
         :verified?    (some? (:email_verified_at session))})))

  (write-session [_ token data]
    (cond
      ;; Already persisted under this token. The map is rebuilt from the
      ;; database on read, so there is nothing new to save, and returning the
      ;; token unchanged keeps the cookie stable across requests.
      token token

      ;; Signing in puts :account-id in the map. Without one there is nothing
      ;; the session could authenticate, so no row is written and nil tells
      ;; Ring to leave the cookie empty.
      (:account-id data)
      (let [fresh (crypto/generate-token)]
        (db/create-session! database
                            {:account-id (:account-id data)
                             :token-hash  (hash-of fresh)
                             :expires-at  (db/expiry ttl-days)})
        fresh)

      :else nil))

  (delete-session [_ token]
    ;; Revoke server-side even though the cookie is on its way out. A browser
    ;; that kept a copy must not be able to keep using it, and other sessions
    ;; belonging to the same account are left alone.
    (when (present? token)
      (db/revoke-session-by-token! database (hash-of token)))
    ;; Returning nil is what removes the cookie rather than rewriting it.
    nil))

(defn session-store
  "A Ring session store that reads and writes the sessions table."
  [database]
  (->PostgresSessionStore database))
