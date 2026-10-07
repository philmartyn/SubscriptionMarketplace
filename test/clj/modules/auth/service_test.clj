(ns modules.auth.service-test
  "Tests for signup, sign in and email verification.

  The rules live here rather than in the handlers, so these are the assertions
  that hold when the HTTP layer is added: what counts as taken, that a wrong
  password and an unknown email are indistinguishable, and that a verification
  link stops working after one use.

  One use-fixtures call, deliberately. clojure.test stores fixtures with assoc
  rather than conj, so a second (use-fixtures :each ...) silently discards the
  first instead of adding to it - the tests would then run with no database and
  fail for reasons that have nothing to do with what they assert."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [modules.auth.crypto :as crypto]
   [modules.auth.db :as db]
   [modules.auth.mailer :as mailer]
   [modules.auth.service :as service]
   [modules.auth.support :as sup]
   [next.jdbc :as jdbc]))

(def ^:private config {:base-url "http://localhost:3006"})
(def ^:private password "correct horse battery staple")

(def ^:private recording
  "A recording mailer and the atom it writes to, built once at load."
  (mailer/recording-mailer))

(def ^:private test-mailer (first recording))
(def ^:private captured (second recording))

(use-fixtures :each
  sup/with-db
  sup/with-fast-argon2
  (fn [f]
    (reset! captured [])
    (f)))

(defn- rows-of
  "Every row of a table, run outside a transaction so the datasource's builder-fn
  applies and the keys come back unqualified."
  [table]
  (jdbc/execute! sup/*db* [(str "select * from " (name table))]))

(defn- register!
  "Sign up through a mailer that records instead of sending."
  [input]
  (service/register! sup/*db* test-mailer config input))

(defn- token-from
  "The opaque token out of a verification URL."
  [url]
  (second (re-find #"token=([^&]+)" url)))

;;; what signup writes

(deftest a-subscriber-is-not-a-vendor
  (let [r (register! {:account-type :user :email "ada@example.com"
                      :password password :name "Ada"})]
    (is (true? (:ok? r)))
    (is (= :user (:account-type r)))
    (is (= 1 (count (rows-of :users))))
    (is (empty? (rows-of :vendors))
        "a subscriber has no vendor row")))

(deftest a-vendor-is-not-a-subscriber
  (let [r (register! {:account-type :vendor :email "vic@example.com"
                      :password password :name "Vic Coffee"
                      :description "Roaster"})]
    (is (true? (:ok? r)))
    (is (= :vendor (:account-type r)))
    (is (= 1 (count (rows-of :vendors))))
    (is (empty? (rows-of :users)) "a vendor has no users row")))

(deftest signup-needs-a-name-only-for-vendors
  (testing "a subscriber may sign up without one"
    (is (true? (:ok? (register! {:account-type :user :email "a@example.com"
                                 :password password})))))
  (testing "a vendor is a public identity, so a name is required"
    (let [r (register! {:account-type :vendor :email "b@example.com"
                        :password password :name "  "})]
      (is (false? (:ok? r)))
      (is (str/includes? (str (:name (:errors r))) "name")))))

(deftest the-typed-email-is-kept-and-also-normalized
  (register! {:account-type :user :email "Ada@Example.COM" :password password})
  (let [row (db/find-account-by-email sup/*db* "ada@example.com")]
    (is (= "Ada@Example.COM" (:email row)) "what the customer typed is kept")
    (is (= "ada@example.com" (:email_normalized row))
        "the lookup key is lowercased")
    (is (= "Ada@Example.COM"
           (:email (db/find-account-by-id sup/*db* (:id row)))))))

(deftest no-plaintext-password-lands-in-the-database
  (register! {:account-type :user :email "ada@example.com" :password password})
  (doseq [table [:accounts :users]]
    (is (not (str/includes? (pr-str (rows-of table)) password))
        (str "the password must not appear in " table))))

;;; what signup refuses

(deftest an-impossible-password-is-refused
  (let [r (register! {:account-type :user :email "a@example.com" :password "short"})]
    (is (false? (:ok? r)))
    (is (str/includes? (str (:password (:errors r))) "12"))))

(deftest a-malformed-email-is-refused
  (doseq [email ["" "nope" "no-at-sign.com" "spaces in@example.com"]]
    (let [r (register! {:account-type :user :email email :password password})]
      (is (false? (:ok? r)) (str (pr-str email) " should be refused"))
      (is (some? (:email (:errors r))) "the error is keyed by the field"))))

(deftest each-error-is-keyed-to-the-field-that-caused-it
  (let [r (register! {:account-type :user :email "nope" :password "x"})]
    (is (= #{"email" "password"}
           (set (map (comp name key) (:errors r)))))))

(deftest the-email-lookup-is-case-insensitive
  (register! {:account-type :user :email "Ada@Example.com" :password password})
  (let [r (register! {:account-type :user :email "ada@EXAMPLE.COM"
                      :password password})]
    (is (false? (:ok? r)) "a mixed-case duplicate counts as the same account")
    (is (str/includes? (str (:email (:errors r))) "already exists"))))

(deftest a-refused-signup-writes-nothing
  (register! {:account-type :user :email "ok@example.com" :password password})
  (register! {:account-type :user :email "ok@example.com" :password password})
  (is (= 1 (count (rows-of :accounts)))
      "the second attempt was refused, so it must not have created anything"))

(deftest an-unknown-account-type-is-refused-rather-than-thrown
  (let [r (register! {:account-type :admin :email "x@example.com"
                      :password password})]
    (is (false? (:ok? r))
        "a caller's typo should come back as a validation error, not an exception")
    (is (some? (:account-type (:errors r))))
    (is (empty? (rows-of :accounts)) "and nothing was written")))

;;; the verification mail

(deftest signup-sends-exactly-one-verification-mail
  (let [r (register! {:account-type :user :email "ada@example.com"
                      :password password})]
    (is (= 1 (count @captured)) "one message per signup")
    (let [message (first @captured)]
      (is (= "ada@example.com" (:to message)))
      (is (str/includes? (:subject message) "Confirm"))
      (is (str/includes? (:body message) (:verification-url r))
          "the link that was mailed is the link that was issued")
      (is (str/includes? (:body message) "localhost:3006")
          "the link is absolute, so it can be opened from an email client"))))

(deftest the-verification-link-lands-on-the-verify-route
  (let [r (register! {:account-type :user :email "ada@example.com"
                      :password password})]
    (is (str/starts-with? (:verification-url r)
                          "http://localhost:3006/auth/verify?token="))))

;;; sign in

(deftest the-right-password-signs-in
  (register! {:account-type :user :email "ada@example.com" :password password})
  (let [r (service/authenticate sup/*db* {:email "ada@example.com"
                                          :password password})]
    (is (true? (:ok? r)))
    (is (= "ada@example.com" (:email (:account r))))
    (is (false? (:verified? r)) "a fresh account has not verified yet")))

(deftest the-email-is-looked-up-case-insensitively
  (register! {:account-type :user :email "Ada@Example.com" :password password})
  (is (true? (:ok? (service/authenticate sup/*db*
                                         {:email "ADA@EXAMPLE.com" :password password})))))

(deftest a-wrong-password-and-an-unknown-email-say-the-same-thing
  (register! {:account-type :user :email "ada@example.com" :password password})
  (let [wrong (service/authenticate sup/*db*
                                    {:email "ada@example.com" :password "not the password"})
        unknown (service/authenticate sup/*db*
                                      {:email "nobody@example.com" :password password})]
    (is (false? (:ok? wrong)))
    (is (false? (:ok? unknown)))
    (is (= (:errors wrong) (:errors unknown))
        (str "if these differ, response content reveals whether an email is "
             "registered, and the endpoint becomes an account oracle"))))

(deftest an-unverified-account-can-still-sign-in
  (register! {:account-type :user :email "ada@example.com" :password password})
  (let [r (service/authenticate sup/*db* {:email "ada@example.com"
                                          :password password})]
    (is (true? (:ok? r))
        "interrupting sign-in would leave no way back in to finish verifying")
    (is (false? (:verified? r)) "but it is not marked verified either")))

(deftest a-weak-hash-is-replaced-the-next-time-someone-signs-in
  (let [r (register! {:account-type :user :email "ada@example.com"
                      :password password})
        account-id (:account-id r)]
    ;; Stand in for a hash written before cost was raised.
    (with-redefs [crypto/argon2-params {:memory 64 :iterations 1 :parallelism 1}]
      (db/update-password-hash! sup/*db* account-id (crypto/hash-password password)))

    (testing "the weakened hash is detected before signing in"
      (is (true? (crypto/password-hash-needs-rehash?
                  (:password_hash (db/find-account-by-id sup/*db* account-id))))))

    (let [signed-in (service/authenticate sup/*db*
                                          {:email "ada@example.com" :password password})]
      (is (true? (:ok? signed-in)) "the account still signs in either way")
      (is (true? (:needs-rehash? signed-in))))

    (testing "and the stored hash has caught up"
      (is (false? (crypto/password-hash-needs-rehash?
                   (:password_hash (db/find-account-by-id sup/*db* account-id))))
          "otherwise every signin would rehash again"))))

;;; verifying the email

(deftest a-verification-link-works-once
  (let [r (register! {:account-type :user :email "ada@example.com"
                      :password password})
        token (token-from (:verification-url r))]
    (is (true? (:ok? (service/verify-email sup/*db* token))))
    (is (true? (db/email-verified?
                (db/find-account-by-id sup/*db* (:account-id r)))))
    (let [replay (service/verify-email sup/*db* token)]
      (is (false? (:ok? replay)))
      (is (= :invalid (:reason replay))
          "a replay must not be distinguishable from a bogus token"))))

(deftest an-expired-or-forged-link-is-refused
  (let [r (register! {:account-type :user :email "ada@example.com"
                      :password password})]
    (is (= {:ok? false :reason :invalid}
           (service/verify-email sup/*db* "not-a-real-token")))
    (is (false? (db/email-verified?
                 (db/find-account-by-id sup/*db* (:account-id r))))
        "a bad token leaves the account unverified")))

(deftest resending-invalidates-the-earlier-link
  (let [r (register! {:account-type :user :email "ada@example.com"
                      :password password})
        first-token (token-from (:verification-url r))
        account-id (:account-id r)]
    (is (true? (:ok? (service/resend-verification sup/*db* test-mailer
                                                  config account-id))))
    (is (= 2 (count @captured)) "the resend produced a second message")
    (is (false? (:ok? (service/verify-email sup/*db* first-token)))
        (str "the link a customer already clicked must stop working, or "
             "resending would leave two live links"))))

(deftest resending-is-refused-once-verified
  (let [r (register! {:account-type :user :email "ada@example.com"
                      :password password})
        account-id (:account-id r)]
    (service/verify-email sup/*db* (token-from (:verification-url r)))
    (let [again (service/resend-verification sup/*db* test-mailer
                                             config account-id)]
      (is (false? (:ok? again)))
      (is (str/includes? (str (:email (:errors again))) "already verified")))))

(deftest resending-to-an-unknown-account-is-refused
  (is (= {:ok? false :reason :unknown-account}
         (service/resend-verification sup/*db* test-mailer config 99999))))
