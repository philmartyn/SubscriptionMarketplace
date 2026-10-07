(ns modules.auth.db-test
  "Tests for the SQL layer.

  Two of these exist purely to pin a bug that failed silently: an insert inside
  a transaction came back keyed after its table, so the row was written but
  (:id row) was nil, and the profile insert then violated a not-null
  constraint. Nothing in the failure named the real cause."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [modules.auth.crypto :as crypto]
   [modules.auth.db :as db]
   [modules.auth.support :as sup]
   [next.jdbc :as jdbc]))

(use-fixtures :each
  sup/with-db
  sup/with-fast-argon2)

(defn- rows
  "Every row of a table, run outside a transaction so the datasource's builder-fn
  applies and the keys come back unqualified. `table` is a keyword, so it is
  named here rather than stringified - str would render it as \":users\"."
  [table]
  (jdbc/execute! sup/*db* [(str "select * from " (name table))]))

(defn- profile-rows [table] (rows table))

(defn- live-sessions-for
  [account-id]
  (jdbc/execute! sup/*db*
                 ["select * from sessions where account_id = ? and revoked_at is null"
                  account-id]))

(defn- account!
  "A registered account, so tests start from a known place."
  ([] (account! "ada@example.com" :user))
  ([email] (account! email :user))
  ([email account-type]
   (db/register! sup/*db*
                 {:email            email
                  :email-normalized email
                  :password-hash    (crypto/hash-password "correct horse battery staple")
                  :account-type     account-type
      ;; Both profile tables declare a name. Passing both keys is safe: each
      ;; writer destructures only what it inserts.
                  :name             (str "Name for " email)
                  :description      "A test account"})))

(defn- token!
  "A verification token for account-id, optionally already expired."
  [account-id & [{:keys [expired?]}]]
  (let [hash (str "hash-" (java.util.UUID/randomUUID))]
    (db/create-verification-token! sup/*db*
                                   {:account-id account-id
                                    :token-hash hash
                                    :expires-at (if expired?
                                                  (.minus (java.time.Instant/now) (java.time.Duration/ofHours 1))
                                                  (db/expiry 1))})
    hash))

(defn- session!
  [account-id & [{:keys [expired?]}]]
  (let [hash (str "session-" (java.util.UUID/randomUUID))]
    (db/create-session! sup/*db*
                        {:account-id account-id
                         :token-hash hash
                         :expires-at (if expired?
                                       (.minus (java.time.Instant/now) (java.time.Duration/ofDays 1))
                                       (db/expiry 30))})
    hash))

;;; registration

(deftest an-account-inside-a-transaction-returns-unqualified-keys
  (testing "regression: builder-fn does not survive jdbc/with-transaction"
    (let [account (account!)]
      (is (pos? (:id account))
          (str "(:id account) should be a positive number, but was "
               (pr-str (:id account))
               " - if it is nil the insert returned #:accounts{:id n}"))
      (is (not (contains? account :accounts/id))
          "keys must not be namespaced after their table"))))

(deftest registration-writes-only-the-profile-for-the-requested-type
  (let [user (account! "ada@example.com" :user)
        vendor (account! "vic@example.com" :vendor)]
    (testing "a subscriber"
      (is (= 1 (count (profile-rows :users))))
      (is (= (:id user) (:account_id (first (profile-rows :users))))))

    (testing "a vendor"
      (is (= 1 (count (profile-rows :vendors))))
      (is (= (:id vendor) (:account_id (first (profile-rows :vendors))))))

    (testing "they are separate entities, not two roles on one account"
      (is (not= (:id user) (:id vendor)))
      (is (empty? (filter #(= (:id vendor) (:account_id %)) (rows :users)))
          "the vendor has no users row")
      (is (empty? (filter #(= (:id user) (:account_id %)) (rows :vendors)))
          "the subscriber has no vendors row"))))

(deftest an-unknown-account-type-is-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unknown account type"
                        (account! "x@example.com" :admin))
      "silently writing an account with no profile would be worse than failing"))

(deftest registration-is-atomic
  (testing "a profile row is never written for an account that was not"
    (is (thrown? clojure.lang.ExceptionInfo
                 (db/register! sup/*db* {:email "e@x.com"
                                         :email-normalized "e@x.com"
                                         :password-hash "h"
                                         :account-type :nope})))
    (is (empty? (profile-rows :users)) "no orphan users row")
    (is (empty? (profile-rows :vendors)) "no orphan vendors row")))

(deftest looking-up-by-email-is-an-exact-match
  (let [account (account! "ada@example.com")]
    (is (some? (db/find-account-by-email sup/*db* "ada@example.com")))
    (is (= (:id account)
           (:id (db/find-account-by-email sup/*db* "ada@example.com"))))
    (is (nil? (db/find-account-by-email sup/*db* "other@example.com"))
        "case folding happens in the service, on both sides of the lookup")
    (is (= "ada@example.com"
           (:email (db/find-account-by-id sup/*db* (:id account)))))))

;;; email verification

(deftest a-new-account-is-unverified-and-can-be-marked-verified
  (let [account (account!)]
    (is (false? (db/email-verified? account)))
    (db/mark-email-verified! sup/*db* (:id account))
    (is (true? (db/email-verified?
                (db/find-account-by-id sup/*db* (:id account)))))))

(deftest rehashing-replaces-the-stored-hash
  (let [account (account!)]
    (db/update-password-hash! sup/*db* (:id account) "a new hash")
    (is (= "a new hash"
           (:password_hash (db/find-account-by-id sup/*db* (:id account)))))))

;;; sessions

(deftest a-live-session-joins-to-its-account
  (let [account (account!)
        ;; A second session first, so this account's session id (2) differs
        ;; from its account id (1). With one row of each both would be 1 and
        ;; the shadowing assertion below would pass by coincidence.
        _ (session! (:id account))
        hash (session! (:id account))
        found (db/find-active-session sup/*db* hash)]
    (is (some? found) "an unexpired, unrevoked session is found")
    (is (= (:id account) (:account_id found)))
    (is (= "ada@example.com" (:email found)))
    (is (= "user" (:account_type found)))
    (testing "session columns are aliased so the join does not shadow them"
      (is (= 2 (:session_id found))
          "the session's own id, not the account's")
      (is (not= (:session_id found) (:account_id found))))))

(deftest a-revoked-session-is-not-found
  (is (nil? (db/find-active-session sup/*db* "no-such-hash")))
  (let [account (account!)
        hash (session! (:id account))
        session-id (:session_id (db/find-active-session sup/*db* hash))]
    (is (some? (db/find-active-session sup/*db* hash)))
    (is (= 1 (db/revoke-session! sup/*db* session-id))
        "first revocation reports one row")
    (is (nil? (db/find-active-session sup/*db* hash))
        "it stops working immediately, while its row remains")))

(deftest revoking-twice-reports-nothing-the-second-time
  (let [account (account!)
        hash (session! (:id account))
        session-id (:session_id (db/find-active-session sup/*db* hash))]
    (is (= 1 (db/revoke-session! sup/*db* session-id)))
    (is (= 0 (db/revoke-session! sup/*db* session-id))
        "the count is what distinguishes a first revoke from a replay")))

(deftest an-expired-session-is-not-found
  (let [account (account!)]
    (is (nil? (db/find-active-session sup/*db*
                                      (session! (:id account) {:expired? true}))))))

(deftest revoking-all-leaves-no-live-session
  (let [account (account!)]
    (session! (:id account))
    (session! (:id account))
    (is (= 2 (db/revoke-all-sessions! sup/*db* (:id account))))
    (is (= 0 (db/revoke-all-sessions! sup/*db* (:id account)))
        "already-revoked rows are not counted again")
    (is (empty? (live-sessions-for (:id account))))))

(deftest a-foreign-account-is-unaffected-when-revoking-all
  (let [one (account! "one@example.com")
        two (account! "two@example.com")]
    (session! (:id one))
    (session! (:id two))
    (is (= 1 (db/revoke-all-sessions! sup/*db* (:id one))))
    (is (= 1 (count (live-sessions-for (:id two))))
        "revoking one account must not sign out another")))

(deftest purging-removes-expired-rows-and-keeps-live-ones
  (let [account (account!)]
    (session! (:id account) {:expired? true})
    (session! (:id account))
    (is (pos? (db/purge-expired-sessions! sup/*db*)))
    (is (= 1 (count (live-sessions-for (:id account))))
        "the live session survived")
    (is (= 0 (db/purge-expired-sessions! sup/*db*))
        "nothing left to purge")))

;;; verification tokens

(deftest a-token-is-found-only-while-it-is-live
  (let [account (account!)
        hash (token! (:id account))]
    (is (some? (db/find-live-verification-token sup/*db* hash)))
    (is (nil? (db/find-live-verification-token sup/*db* "not-a-token")))))

(deftest consuming-a-token-is-single-shot
  (let [account (account!)
        hash (token! (:id account))]
    (is (= 1 (db/consume-verification-token! sup/*db* hash))
        "first use reports one row")
    (is (= 0 (db/consume-verification-token! sup/*db* hash))
        (str "a replay reports zero, which is the only thing that makes the "
             "token single use. An UPDATE with :return-keys returns the row "
             "instead, which says nothing about what matched."))
    (is (nil? (db/find-live-verification-token sup/*db* hash))
        "and the token is no longer live")))

(deftest an-expired-token-is-not-found
  (let [account (account!)]
    (is (nil? (db/find-live-verification-token sup/*db*
                                               (token! (:id account) {:expired? true}))))))

(deftest superseding-voids-every-outstanding-token
  (let [account (account!)
        first-hash (token! (:id account))
        second-hash (token! (:id account))]
    (is (= 2 (db/supersede-verification-tokens! sup/*db* (:id account))))
    (is (nil? (db/find-live-verification-token sup/*db* first-hash))
        "a link mailed earlier stops working when a newer one is issued")
    (is (nil? (db/find-live-verification-token sup/*db* second-hash)))
    (is (= 0 (db/supersede-verification-tokens! sup/*db* (:id account)))
        "nothing outstanding to void")))

(deftest superseding-leaves-other-accounts-alone
  (let [one (account! "one@example.com")
        two (account! "two@example.com")
        theirs (token! (:id two))]
    (db/supersede-verification-tokens! sup/*db* (:id one))
    (is (some? (db/find-live-verification-token sup/*db* theirs)))))

(deftest account-ids-look-up-from-their-profile
  (let [user (account! "ada@example.com" :user)
        vendor (account! "vic@example.com" :vendor)
        user-row (first (rows :users))
        vendor-row (first (rows :vendors))]
    (is (= (:id user) (db/find-account-id-by-user-id sup/*db* (:id user-row))))
    (is (= (:id vendor) (db/find-account-id-by-vendor-id sup/*db* (:id vendor-row))))
    (is (nil? (db/find-account-id-by-user-id sup/*db* 99999)))))
