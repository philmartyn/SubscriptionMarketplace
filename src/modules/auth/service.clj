(ns modules.auth.service
  "Signup, sign in and email verification.

  This is the only place that knows the rules: what a valid password is, when an
  email counts as taken, and what happens when a verification link is replayed.
  Handlers translate HTTP to these calls and back, and nothing else.

  Every function returns a result map rather than throwing, so that a handler can
  render the failure next to the form that caused it.

  Subscribers and vendors are separate entities with separate signup calls. The
  account_type decides which profile row is written, and there is no path here
  that turns one into the other: becoming a vendor is an explicit promotion, not
  a second signup."
  (:require
   [clojure.string :as str]
   [modules.auth.crypto :as crypto]
   [modules.auth.db :as db]
   [modules.auth.mailer :as mailer]
   [shared.telemetry :as telemetry]))

(def min-password-length
  "NIST 800-63B guidance is a minimum of 8, but 12 is cheap to type and
  meaningfully stronger against guessing. Length beats composition rules."
  12)

(def verification-ttl-hours 24)

(def session-ttl-days 30)

(def account-types #{:user :vendor})

;;; validation

(defn normalize-email
  "The form an email is stored and looked up in.

  Trimming and lowercasing here is what makes sign in case insensitive, and the
  unique index on lower(email_normalized) is the database agreeing."
  [email]
  (some-> email str str/trim str/lower-case))

(defn- email-error
  [email]
  (cond
    (str/blank? (str email)) "Enter your email address."
    (not (re-matches #"(?i)[^@\s]+@[^@\s.]+\.[^@\s]+" (str/trim (str email))))
    "That does not look like an email address."
    :else nil))

(defn- password-error
  [password]
  (cond
    (str/blank? (str password)) "Choose a password."
    (< (count (str password)) min-password-length)
    (str "Use at least " min-password-length " characters.")
    :else nil))

(defn- name-error
  [account-type name]
  (when (= account-type :vendor)
    (when (str/blank? (str name))
      "Tell us the name your customers will see.")))

(defn- account-type-error
  "Nil for :user and :vendor, which are the only two kinds of account.

  Checked here rather than left to the database's CHECK constraint, because
  register! is documented to answer with a result map rather than throw, and
  a PSQLException for a caller's typo is neither."
  [account-type]
  (when-not (contains? account-types account-type)
    "Unknown account type."))

(defn- validation-errors
  [account-type {:keys [email password name]}]
  (cond-> {}
    (account-type-error account-type) (assoc :account-type (account-type-error account-type))
    (email-error email)     (assoc :email (email-error email))
    (password-error password) (assoc :password (password-error password))
    (name-error account-type name) (assoc :name (name-error account-type name))))

;;; verification tokens

(defn- verification-url
  [config token]
  (str (:base-url config) "/auth/verify?token=" token))

(defn- issue-verification!
  "Mail a fresh verification link, invalidating any earlier one.

  Superseding first is what stops a link that has already been mailed from
  still working after the customer asks for a new one."
  [db account-id]
  (db/supersede-verification-tokens! db account-id)
  (let [token (crypto/generate-token)]
    (db/create-verification-token!
     db
     {:account-id account-id
      :token-hash  (crypto/token-hash token)
       ;; Instant/now takes a Clock, not a Duration, so the duration has to be
       ;; added to the instant. Handing it to now/ reflects onto now(Clock)
       ;; and blows up at runtime with a ClassCastException.
      :expires-at  (.plus (java.time.Instant/now)
                          (java.time.Duration/ofHours verification-ttl-hours))})
    token))

;;; signup

(defn register!
  "Create an account with its profile, then email a verification link.

  Returns {:ok? true :account-id n :account-type k :verification-url u} or
  {:ok? false :errors {...}} with errors keyed by form field. Never throws for
  input a caller could have got wrong."
  [db mailer config {:keys [account-type email password name description] :as input}]
  ;; clojure.core/name is qualified because `name` is destructured from input
  ;; below, and the local string would otherwise be called as a function.
  (telemetry/with-span "auth.register" {:account_type (some-> account-type clojure.core/name)}
    (let [errors (validation-errors account-type input)]
      (if (seq errors)
        (do
          (telemetry/incr! "auth.signup.rejected" {:reason "validation"})
          {:ok? false :errors errors})
        (let [normalized (normalize-email email)]
          (if (db/find-account-by-email db normalized)
            (do
              (telemetry/incr! "auth.signup.rejected" {:reason "duplicate-email"})
              {:ok? false :errors {:email "An account already exists for that email."}})
            (let [{:keys [id] :as account}
                  (db/register! db (assoc input
                                          :email             (str/trim (str email))
                                          :email-normalized  normalized
                                          :password-hash     (crypto/hash-password password)
                                          :account-type      account-type))
                  url (verification-url config (issue-verification! db id))]
              (mailer/send-email
               mailer
               {:to      (str/trim (str email))
                :subject "Confirm your email address"
                :body    (str "Welcome to SubMarket. Confirm your email address to "
                              "activate your account:\n\n" url "\n\n"
                              "The link is valid for " verification-ttl-hours " hours.")})
              (telemetry/incr! "auth.signup" {:account_type (some-> account-type clojure.core/name)})
              {:ok? true
               :account-id id
               ;; The keyword that came in, not (:account_type account): accounts
               ;; stores that as text, and handing a caller a string back for a
               ;; keyword input makes every consumer cast it again.
               :account-type account-type
               :verification-url url})))))))

(defn verification-email-body
  "The link text, so a development run can surface it without sending mail."
  [config token]
  (verification-url config token))

;;; sign in

(def ^:private dummy-hash
  "A hash of a value nobody can supply.

  Verifying against this when the email is unknown costs the same Argon2 work as
  a real attempt, so response time does not reveal whether an account exists."
  (delay (crypto/hash-password (str "no account has this password " (java.util.UUID/randomUUID)))))

(defn authenticate
  "Check an email and password.

  Success returns {:ok? true :account {...} :needs-rehash? bool}. Any failure
  returns the same {:ok? false :errors {:credentials ...}} whether the email is
  unknown, unverified or the password is wrong, so the response cannot be used
  to enumerate accounts.

  Unverified accounts are allowed in. Verification is required, but interrupting
  it at the door leaves people with no way back in to finish it."
  [db {:keys [email password]}]
  (telemetry/with-span "auth.authenticate" {}
    (let [normalized (normalize-email email)
          account (db/find-account-by-email db normalized)
          ;; The verify call is deliberately outside the `and` below. Short
          ;; circuiting on a nil account would skip the Argon2 work entirely and
          ;; make an unknown email answer measurably faster than a wrong password.
          password-ok (crypto/verify-password
                       (str password)
                       (if account (:password_hash account) @dummy-hash))]
      (if-not (and account password-ok)
        (do
          (telemetry/incr! "auth.signin.failure")
          {:ok? false :errors {:credentials "That email and password do not match."}})
        (let [needs-rehash? (crypto/password-hash-needs-rehash? (:password_hash account))]
          (when needs-rehash?
            ;; Opportunistic upgrade: the account still signs in either way, but
            ;; the stored hash catches up to the current cost.
            (db/update-password-hash! db (:id account) (crypto/hash-password password)))
          (telemetry/incr! "auth.signin.success" {:account_type (some-> (:account_type account) name)})
          {:ok? true
           :account account
           :verified? (db/email-verified? account)
           :needs-rehash? needs-rehash?})))))

;;; verification

(defn verify-email
  "Consume a verification token.

  Single use: the row is marked consumed and a replay finds nothing. Returns
  {:ok? false :reason :invalid} for an unknown, expired or already-used token
  rather than distinguishing between them."
  [db token]
  (telemetry/with-span "auth.verify_email" {}
    (if-let [{:keys [id account_id]} (some->> token
                                              not-empty
                                              crypto/token-hash
                                              (db/find-live-verification-token db))]
      (if (pos? (or (db/consume-verification-token! db (crypto/token-hash token)) 0))
        (do
          (db/mark-email-verified! db account_id)
          (telemetry/incr! "auth.verify.success")
          {:ok? true :account-id account_id :token-id id})
        (do
          (telemetry/incr! "auth.verify.invalid" {:reason "already-used"})
          {:ok? false :reason :invalid}))
      (do
        (telemetry/incr! "auth.verify.invalid" {:reason "unknown-or-expired"})
        {:ok? false :reason :invalid}))))

(defn verify-account!
  "Mark an account verified without consuming a token.

  The development stand-in for clicking the link in the verification email,
  used while no real mailer is wired. It is a deliberate bypass, so the HTTP
  handler only reaches it behind the :dev-verify? flag (see
  modules.auth.handlers/verify-now). Marking is idempotent: an account that is
  already verified keeps its original timestamp, so a double submit is
  harmless."
  [db account-id]
  (when-let [account (db/find-account-by-id db account-id)]
    (when-not (db/email-verified? account)
      (db/mark-email-verified! db account-id)))
  {:ok? true})

(defn resend-verification
  "Issue a new link for an account that has not yet verified."
  [db mailer config account-id]
  (telemetry/with-span "auth.resend_verification" {}
    (if-let [{:keys [email] :as account} (db/find-account-by-id db account-id)]
      (if (db/email-verified? account)
        {:ok? false :errors {:email "That address is already verified."}}
        (let [url (verification-url config (issue-verification! db account-id))]
          (mailer/send-email
           mailer
           {:to      email
            :subject "Confirm your email address"
            :body    (str "Confirm your email address to activate your account:\n\n" url)})
          (telemetry/incr! "auth.resend_verification")
          {:ok? true :verification-url url}))
      {:ok? false :reason :unknown-account})))

(defn account-type-of
  "The keyword form of an account's type, for route guards.

  account_type is stored as text ('user' or 'vendor'), not an enum, so that
  adding a role later does not need a Postgres type."
  [account]
  (some-> (:account_type account) keyword))
