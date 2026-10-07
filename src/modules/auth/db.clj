(ns modules.auth.db
  "Data access for authentication.

  Every function takes the connectable as its first argument so it can be
  called with a datasource directly in tests, and with the transaction handle
  inside register! without a second code path."
  (:require
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

(def ^:private row-options
  "Applied to every statement, not just those on a datasource.

  A transaction handle is a raw java.sql.Connection, which does not inherit the
  builder-fn configured on the datasource. next.jdbc also namespaces
  generated-key columns after the insert target, so an insert inside a
  transaction comes back as #:accounts{:id 1} and (:id row) is nil. Neither
  failure is loud: the row is written, it is just keyed wrongly."
  {:builder-fn rs/as-unqualified-lower-maps})

(defn- one
  "Single row, or nil."
  [db query & [opts]]
  (jdbc/execute-one! db query (merge row-options opts)))

(defn- many
  "All rows."
  [db query & [opts]]
  (jdbc/execute! db query (merge row-options opts)))

(defn- affected
  "Rows changed by an UPDATE or DELETE.

  execute-one! with :return-keys returns the updated row for an UPDATE, which
  says nothing about whether anything matched, and returns nil both when nothing
  matched and when it did. The update count is what callers need in order to
  distinguish a first use from a replay."
  [db query]
  (:next.jdbc/update-count (first (jdbc/execute! db query row-options))))

;;; coercion
;;
;; Timestamps are passed around as java.time.Instant because that is what
;; clojure.core/time understands, and converted at the boundary because the
;; Postgres driver is happiest with java.sql.Timestamp.

(defn- ->ts
  [instant]
  (java.sql.Timestamp/from instant))

(defn now-ts []
  (->ts (java.time.Instant/now)))

(defn expiry
  "An Instant n days from now.

  Instant/now takes a Clock, not a Duration, so the duration is added to the
  instant rather than passed to now/."
  [n]
  (.plus (java.time.Instant/now) (java.time.Duration/ofDays n)))

;;; accounts

(defn create-account!
  "Insert the credential row. Identity lives here, not in users or vendors."
  [db {:keys [email email-normalized password-hash account-type]}]
  (one db
       (sql/format {:insert-into :accounts
                    :values [{:email            email
                              :email_normalized email-normalized
                              :password_hash    password-hash
                              :account_type     (name account-type)}]})
       {:return-keys true}))

(defn create-user-profile!
  [db {:keys [account-id name]}]
  (one db
       (sql/format {:insert-into :users
                    :values [{:account_id account-id
                              :name       name}]})
       {:return-keys true}))

(defn create-vendor-profile!
  [db {:keys [account-id name description]}]
  (one db
       (sql/format {:insert-into :vendors
                    :values [{:account_id  account-id
                              :name        name
                              :description description}]})
       {:return-keys true}))

(defn register!
  "Create an account and its matching profile atomically.

  account-type decides which profile table is written. A subscriber and a
  vendor are separate entities: an account is promoted to the other role by
  an explicit future flow, never by a second signup reusing this function.

  Returns the created account row."
  [db {:keys [account-type] :as input}]
  ;; Resolve the profile writer before anything is written. accounts carries its
  ;; own CHECK on account_type, so letting the insert run first means an unknown
  ;; type surfaces as a PSQLException naming a constraint rather than as this
  ;; error naming the actual mistake - and it would leave nothing behind either
  ;; way, because the transaction rolls back.
  (let [write-profile (case account-type
                        :user   create-user-profile!
                        :vendor create-vendor-profile!
                        (throw (ex-info "Unknown account type"
                                        {:account-type account-type})))]
    (jdbc/with-transaction [tx db]
      (let [account (create-account! tx input)]
        (when-not (:id account)
          (throw (ex-info "Account insert did not return an id" {:account account})))
        (write-profile tx (assoc input :account-id (:id account)))
        (assoc account :account_type (name account-type))))))

(defn find-account-by-email
  "Look up by normalized email. Case insensitive by construction: the
  normalizer lowercases before this is ever called."
  [db email-normalized]
  (one db
       (sql/format {:select [:accounts/*]
                    :from   [:accounts]
                    :where  [:= :email_normalized email-normalized]})))

(defn find-account-by-id
  [db id]
  (one db
       (sql/format {:select [:accounts/*]
                    :from   [:accounts]
                    :where  [:= :id id]})))

(defn email-verified?
  "True when the account has already consumed a verification link."
  [{:keys [email_verified_at]}]
  (some? email_verified_at))

(defn mark-email-verified!
  [db account-id]
  (one db
       (sql/format {:update  :accounts
                    :set    {:email_verified_at (now-ts)}
                    :where  [:= :id account-id]})
       {:return-keys true}))

(defn update-password-hash!
  "Replace the stored hash, for rehashing at signin after cost was raised."
  [db account-id password-hash]
  (one db
       (sql/format {:update :accounts
                    :set   {:password_hash password-hash
                            :updated_at    (now-ts)}
                    :where [:= :id account-id]})
       {:return-keys true}))

;;; sessions

(defn- jsonb
  "A JSONB literal for the driver, so a serialized session map is typed on its
  way in instead of relying on Postgres to coerce a text parameter."
  [s]
  (doto (org.postgresql.util.PGobject.)
    (.setType "jsonb")
    (.setValue s)))

(defn create-session!
  "Insert a session row. account-id may be nil: a visitor holding only a CSRF
  token gets a row too, and is attached to an account when they sign in. data
  is the serialized session map; when absent the column default '{}' applies."
  [db {:keys [account-id token-hash expires-at data ip user-agent]}]
  (one db
       (sql/format {:insert-into :sessions
                    :values [(cond-> {:account_id account-id
                                      :token_hash  token-hash
                                      :expires_at  (->ts expires-at)
                                      :ip          ip
                                      :user_agent  user-agent}
                               ;; Omitting the key, not passing nil: an explicit
                               ;; NULL would defeat the column's DEFAULT.
                               (some? data) (assoc :data (jsonb data)))]})
       {:return-keys true}))

(defn update-session!
  "Persist a new session map under a token, attaching an account when given.

  Called on every response that carries :session - sign-in attaches the account
  id, flash messages rewrite the map. Returns rows changed."
  [db token-hash {:keys [account-id data]}]
  (affected db
            (sql/format {:update :sessions
                         :set    {:account_id account-id
                                  :data       (jsonb data)}
                         :where  [:and [:= :token_hash token-hash]
                                  [:= :revoked_at nil]]})))

(defn find-active-session
  "The session matching a token hash, only if it is still usable.

  A revoked session is not returned even while its row exists, and neither is
  an expired one. The join is a LEFT JOIN on purpose: an anonymous session has
  no account row, and it must still come back readable - its session map is
  what carries the anti-forgery token.

  Session columns are aliased because the account join would otherwise shadow
  :id and :created_at with the account's."
  [db token-hash]
  (one db
       (sql/format {;; honey.sql takes a join as a top-level :join clause whose
                    ;; value is a flat sequence of [table condition table
                    ;; condition ...]. Writing it into :from instead reads as an
                    ;; alias and trips "illegal syntax in select expression",
                    ;; and an :on keyword misaligns the pairing into the same
                    ;; error.
                    :select [[:sessions.id :session_id]
                             [:sessions.account_id :account_id]
                             [:sessions.token_hash :token_hash]
                             [:sessions.data :data]
                             [:sessions.expires_at :expires_at]
                             [:sessions.revoked_at :revoked_at]
                             [:sessions.created_at :session_created_at]
                             [:accounts.email :email]
                             [:accounts.email_normalized :email_normalized]
                             [:accounts.account_type :account_type]
                             [:accounts.email_verified_at :email_verified_at]]
                    :from   [:sessions]
                    :left-join [:accounts [:= :sessions.account_id :accounts.id]]
                    :where  [:and
                             [:= :sessions.token_hash token-hash]
                             [:= :sessions.revoked_at nil]
                             [:> :sessions.expires_at (now-ts)]]})))

(defn revoke-session!
  "Revoke one session. Returns rows changed, so 0 means already gone."
  [db session-id]
  (affected db
            (sql/format {:update :sessions
                         :set   {:revoked_at (now-ts)}
                         :where [:and [:= :id session-id] [:= :revoked_at nil]]})))

(defn revoke-session-by-token!
  "Revoke the session a raw token identifies, in one statement.

  Written for logout, which has only the cookie to go on. A read-then-revoke
  pair would leave a window between the two, and would do nothing at all when
  the session had already expired or been revoked - in which case there is
  still nothing to revoke, but the caller should not have to care.

  Returns rows changed."
  [db token-hash]
  (affected db
            (sql/format {:update :sessions
                         :set   {:revoked_at (now-ts)}
                         :where [:and
                                 [:= :token_hash token-hash]
                                 [:= :revoked_at nil]]})))

(defn revoke-all-sessions!
  "Sign out everywhere. Used on password change and by account owners."
  [db account-id]
  (affected db
            (sql/format {:update :sessions
                         :set   {:revoked_at (now-ts)}
                         :where [:and [:= :account_id account-id] [:= :revoked_at nil]]})))

(defn purge-expired-sessions!
  "Delete rows that can no longer authenticate anyone."
  [db]
  (affected db
            (sql/format {:delete-from [:sessions]
                         :where     [:< :expires_at (now-ts)]})))

;;; email verification tokens

(defn supersede-verification-tokens!
  "Void any outstanding token for an account.

  Called before issuing a new one, so that a resend cannot leave a previously
  mailed link still usable."
  [db account-id]
  (affected db
            (sql/format {:update :verification_tokens
                         :set   {:consumed_at (now-ts)}
                         :where [:and [:= :account_id account-id] [:= :consumed_at nil]]})))

(defn create-verification-token!
  [db {:keys [account-id token-hash expires-at]}]
  (one db
       ;; No table alias: Postgres has no alias for an INSERT target, and
       ;; honey.sql passes an :as straight through into the SQL.
       (sql/format {:insert-into :verification_tokens
                    :values [{:account_id account-id
                              :token_hash  token-hash
                              :expires_at  (->ts expires-at)}]})
       {:return-keys true}))

(defn find-live-verification-token
  "The token matching a hash, only if unconsumed and unexpired."
  [db token-hash]
  (one db
       (sql/format {:select [:vt.*]
                    :from   [[:verification_tokens :vt]]
                    :where  [:and
                             [:= :vt.token_hash token-hash]
                             [:= :vt.consumed_at nil]
                             [:> :vt.expires_at (now-ts)]]})))

(defn consume-verification-token!
  "Mark a token used. Returns rows changed: 1 on first use, 0 on a replay or if
  a concurrent request got there first. That count is what makes the token
  single use."
  [db token-hash]
  (affected db
            (sql/format {:update :verification_tokens
                         :set   {:consumed_at (now-ts)}
                         :where [:and [:= :token_hash token-hash]
                                 [:= :consumed_at nil]]})))

;;; accounts by profile, for dashboards

(defn find-account-id-by-user-id
  [db user-id]
  (:account_id (one db
                    (sql/format {:select [:account_id]
                                 :from   [:users]
                                 :where  [:= :id user-id]}))))

(defn find-account-id-by-vendor-id
  [db vendor-id]
  (:account_id (one db
                    (sql/format {:select [:account_id]
                                 :from   [:vendors]
                                 :where  [:= :id vendor-id]}))))
