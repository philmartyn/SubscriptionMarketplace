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

  The session *map* is stored too, as JSON in the row. Ring's SessionStore
  contract is that read-session returns what write-session was given, and
  something genuinely lives in that map: the CSRF token, which Ring's
  anti-forgery middleware keeps in the session. An anonymous visitor therefore
  has a session row even before they sign in; signing in attaches the account
  to that same row instead of opening a second one.

  Nothing sensitive is derived into the map that was not already on disk in
  the accounts table, and the token in the cookie is the only credential."
  (:require
   [clojure.string :as str]
   [jsonista.core :as json]
   [modules.auth.crypto :as crypto]
   [modules.auth.db :as db]
   [ring.middleware.session.store :as store]
   [shared.db :as sdb]))

(def ^:private ttl-days
  "How long a session survives without being renewed."
  30)

(def ^:private mapper
  "Keys back to keywords, so :ring.middleware.anti-forgery/anti-forgery-token
  survives the JSON round trip as the key Ring reads it back under."
  (json/object-mapper {:decode-key-fn true}))

(defn- hash-of [token]
  (crypto/token-hash token))

(defn- encode [data]
  (json/write-value-as-string data))

(defn- decode
  "The session map as next.jdbc handed it over - a PGobject for a jsonb column.
  Nothing older than '{}' has ever been written."
  [data]
  (json/read-value (if (instance? org.postgresql.util.PGobject data)
                     (.getValue ^org.postgresql.util.PGobject data)
                     (or data "{}"))
                   mapper))

(defn- present?
  "Ring passes nil when the browser has no cookie, and a stale cookie may hold
  an empty or garbage value. Neither should reach the database."
  [token]
  (not (str/blank? (str token))))

(defrecord PostgresSessionStore [database]
  store/SessionStore

  (read-session [_ token]
    (when (present? token)
      (when-let [row (db/find-active-session database (hash-of token))]
        ;; The row is authoritative for who this session belongs to and what
        ;; state it carries. Anonymous: just the persisted map. Authenticated:
        ;; the account fields overlaid, so the session always shows the current
        ;; account_type and verification status, whatever the handler wrote.
        (let [session (decode (:data row))]
          (if (some? (:account_id row))
            (assoc session
                   :account-id   (:account_id row)
                   :account-type (keyword (:account_type row))
                   :email        (:email row)
                   :verified?    (some? (:email_verified_at row)))
            session)))))

  (write-session [_ token data]
    (if token
      (do
        ;; An existing session: persist whatever changed (a flash message, an
        ;; attached account after sign-in) under the same token, so the cookie
        ;; stays stable. If nothing in the map changed, this is a no-op UPDATE.
        (db/update-session! database (hash-of token)
                            {:account-id (:account-id data)
                             :data       (encode data)})
        token)
      (let [fresh (crypto/generate-token)]
        ;; A brand-new session. The map is persisted because it may already
        ;; carry a CSRF token Ring needs on the next POST.
        (db/create-session! database
                            {:account-id (:account-id data)
                             :token-hash  (hash-of fresh)
                             :expires-at  (sdb/expiry ttl-days)
                             :data        (encode data)})
        fresh)))

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