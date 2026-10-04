(ns modules.auth.crypto-test
  "Tests for password hashing and token generation.

  Every test here exists because that behaviour broke silently at least once
  during development. A password verifier that always returns false looks
  exactly like a wrong password, so nothing fails loudly."
  (:require
   [buddy.core.codecs :as codecs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [modules.auth.crypto :as crypto]))

(defn- decode
  "Base64 as stored in a PHC string, restoring the padding that was stripped."
  [^String s]
  (codecs/b64->bytes (str s (apply str (repeat (- 4 (mod (count s) 4)) "=")))))

(def ^:private fast-params
  "Cost low enough to keep the suite quick. The real cost is asserted
  separately in `uses-the-owasp-baseline-cost`."
  {:memory 256 :iterations 1 :parallelism 1})

(def ^:private default-params
  "Captured at load time, before the fixture below rebinds the var, so that the
  baseline assertion checks the shipped default rather than the test override."
  crypto/argon2-params)

(use-fixtures :each (fn [t] (with-redefs [crypto/argon2-params fast-params] (t))))

(def ^:private password "correct horse battery staple")

;;; structure of the stored string

(deftest uses-the-owasp-baseline-cost
  (is (= {:memory 19456 :iterations 2 :parallelism 1} default-params)
      "defaults should match the OWASP Argon2id recommendation"))

(deftest stores-a-well-formed-phc-string
  (let [phc (crypto/hash-password password)
        [_ algorithm version cost _ _] (str/split phc #"\$")]
    (is (= 6 (count (str/split phc #"\$"))) "six $-separated fields")
    (is (= "argon2id" algorithm))
    ;; The version is its own field. Emitting `v=19,m=19456` instead makes the
    ;; cost unparseable and every subsequent verification fail.
    (is (= "v=19" version))
    (is (str/includes? cost "m=") "memory cost present")
    (is (str/includes? cost "t=") "iteration cost present")
    (is (str/includes? cost "p=") "parallelism present")))

(deftest salt-and-hash-are-unpadded-base64
  (let [[_ _ _ _ salt hash] (str/split (crypto/hash-password password) #"\$")]
    (is (not (str/includes? salt "=")) "PHC uses unpadded base64")
    (is (not (str/includes? hash "=")))
    ;; Encoding must go through a String-returning helper. buddy's
    ;; bytes->b64 returns bytes, and stringifying those yields "[B@2e7ba051".
    (is (re-matches #"[A-Za-z0-9+/]+" salt) "salt decodes as base64, not an object")
    (is (re-matches #"[A-Za-z0-9+/]+" hash))
    (is (= 16 (alength (decode salt))) "salt is 16 bytes")
    (is (= 32 (alength (decode hash))) "hash is 32 bytes")))

;;; the round trip, which is the thing that matters

(deftest verifies-the-correct-password
  (is (true? (crypto/verify-password password (crypto/hash-password password)))))

(deftest rejects-the-wrong-password
  (is (false? (crypto/verify-password "wrong password" (crypto/hash-password password)))))

(deftest is-case-sensitive
  (is (false? (crypto/verify-password (str/upper-case password)
                                      (crypto/hash-password password)))))

(deftest rejects-the-empty-password
  (is (false? (crypto/verify-password "" (crypto/hash-password password)))))

(deftest salts-each-hash-uniquely
  (testing "two accounts choosing the same password must not share a hash"
    (let [one (crypto/hash-password password)
          two (crypto/hash-password password)]
      (is (not= one two))
      (is (true? (crypto/verify-password password one)))
      (is (true? (crypto/verify-password password two))))))

;;; cost parameters travel with the hash

(deftest honours-stored-cost-rather-than-current-defaults
  (testing "a hash written at weaker cost still verifies after cost is raised"
    (let [legacy (with-redefs [crypto/argon2-params {:memory 256 :iterations 1 :parallelism 1}]
                   (crypto/hash-password password))]
      (is (true? (crypto/verify-password password legacy))
          "verification reads cost from the stored string, not from current defaults")
      ;; Simulate a later deploy that raises the global cost.
      (with-redefs [crypto/argon2-params {:memory 19456 :iterations 2 :parallelism 1}]
        (is (true? (crypto/password-hash-needs-rehash? legacy))
            "and flags the weaker hash for rehashing on next signin")))))

(deftest does-not-flag-current-cost-for-rehash
  (is (false? (crypto/password-hash-needs-rehash? (crypto/hash-password password)))))

;;; fail closed on anything unexpected

(deftest fails-closed-on-malformed-hashes
  (testing "a corrupt row must never authenticate anyone"
    (doseq [stored [nil
                    ""
                    "not-a-hash"
                    ;; right prefix, nonsense cost
                    "$argon2id$v=19$m=broken"
                    ;; cost fields missing entirely
                    "$argon2id$v=19$$$"
                    ;; truncated after the algorithm
                    "$argon2id$"
                    ;; cost present but salt missing
                    "$argon2id$v=19$m=19456,t=2,p=1$"]]
      (is (false? (crypto/verify-password password stored))
          (str "should reject " (pr-str stored))))))

(deftest fails-closed-on-other-password-algorithms
  (testing "a bcrypt row must not be accepted as if it were Argon2id"
    (is (false? (crypto/verify-password password "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy")))))

(deftest fails-closed-on-malformed-hash-for-rehash-check
  (is (false? (crypto/password-hash-needs-rehash? "$argon2id$v=19$m=broken"))))

;;; tokens

(deftest generate-token-returns-a-url-safe-string
  (let [token (crypto/generate-token)]
    (is (string? token) "must be a String, not a byte array")
    (is (re-matches #"[A-Za-z0-9_-]+" token)
        "url-safe so it survives being pasted into a link unescaped")
    (is (= 43 (count token)) "32 bytes of entropy in unpadded base64")))

(deftest generate-token-is-unpredictable
  (let [tokens (repeatedly 100 crypto/generate-token)]
    (is (= 100 (count (set tokens))) "no collisions across 100 tokens")))

(deftest token-hash-is-deterministic-sha256-hex
  (let [token (crypto/generate-token)
        hashed (crypto/token-hash token)]
    (is (re-matches #"[0-9a-f]{64}" hashed) "sha256 hex")
    (is (= hashed (crypto/token-hash token)) "same token always hashes the same")
    (is (not= token hashed) "the stored value is not the token itself")))

(deftest different-tokens-hash-differently
  (is (not= (crypto/token-hash (crypto/generate-token))
            (crypto/token-hash (crypto/generate-token)))))

;;; constant time comparison

(deftest secure-equals?-compares-exactly
  (is (true? (crypto/secure-equals? "abc" "abc")))
  (is (false? (crypto/secure-equals? "abc" "abd")))
  (is (false? (crypto/secure-equals? "abc" "abcd")) "length mismatch is not equal")
  (is (false? (crypto/secure-equals? "abc" nil)))
  (is (false? (crypto/secure-equals? nil "abc"))))
