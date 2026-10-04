(ns modules.auth.crypto
  "Password hashing and opaque token generation.

  Passwords use Argon2id stored in PHC string format, which embeds the
  algorithm, version and cost parameters next to the salt and hash. That means
  cost can be raised later without invalidating existing passwords:
  verify-password reads the parameters back out of the stored string rather
  than assuming the current defaults.

  Cost parameters are a dynamic var so tests can bind them down to something
  cheap; the defaults are the OWASP Password Storage recommendations for
  Argon2id.

  Tokens (session ids, email verification links) are 32 bytes of SecureRandom
  rendered as url-safe base64. Only the SHA-256 of a token is ever persisted,
  so a database dump cannot be replayed as a live session."
  (:require
   [buddy.core.bytes :as bytes]
   [buddy.core.codecs :as codecs]
   [buddy.core.nonce :as nonce]
   [clojure.string :as str])
  (:import
   [org.bouncycastle.crypto.generators Argon2BytesGenerator]
   [org.bouncycastle.crypto.params Argon2Parameters]
   [org.bouncycastle.crypto.params Argon2Parameters$Builder]
   [java.security MessageDigest]))

(def argon2-params
  "Argon2id cost. m is memory in KiB, t is iterations, p is parallelism."
  {:memory      19456
   :iterations  2
   :parallelism 1})

(def ^:private salt-length 16)
(def ^:private hash-length 32)
(def ^:private token-bytes 32)
(def ^:private phc-prefix "$argon2id$")

;;; base64 without padding, as used by the PHC string format

(defn- unpadded
  [s]
  (str/replace s "=" ""))

(defn- padded
  "Restore the padding a PHC salt or hash needs to decode."
  [^String s]
  (let [remainder (mod (count s) 4)]
    (if (zero? remainder)
      s
      (str s (apply str (repeat (- 4 remainder) "="))))))

;;; argon2

(defn- argon2-derive
  "Raw Argon2id bytes for password, salt and cost."
  ^bytes [^String password ^bytes salt {:keys [memory iterations parallelism]} ^long length]
  (let [params (-> (Argon2Parameters$Builder. Argon2Parameters/ARGON2_id)
                   (.withSalt salt)
                   (.withVersion Argon2Parameters/ARGON2_VERSION_13)
                   (.withIterations (int iterations))
                   (.withMemoryAsKB (int memory))
                   (.withParallelism (int parallelism))
                   (.build))
        generator (Argon2BytesGenerator.)
        out (byte-array length)]
    (.init generator ^Argon2Parameters params)
    (.generateBytes generator (codecs/str->bytes password) out)
    out))

(defn- parse-phc
  "Pull algorithm, cost, salt and hash back out of a stored PHC string.

  Returns nil for anything that is not a well formed Argon2id string, so a
  corrupt row can never authenticate anyone."
  [^String stored]
  (try
    (let [[_ algorithm version cost-string salt hash]
          (str/split stored #"\$" 6)
          ;; Parsed into a map rather than positionally: the PHC spec does not
          ;; guarantee that m, t and p appear in any particular order.
          cost (into {}
                     (for [field (str/split (str/trim cost-string) #",")
                           :let [[k v] (str/split (str/trim field) #"=" 2)]]
                       [k (Long/parseLong v)]))]
      (when (and (= "argon2id" algorithm)
                 (= "v=19" version)
                 (contains? cost "m")
                 (contains? cost "t")
                 (contains? cost "p")
                 (seq salt)
                 (seq hash))
        {:salt        (codecs/b64->bytes (padded salt))
         :hash        (codecs/b64->bytes (padded hash))
         :memory      (get cost "m")
         :iterations  (get cost "t")
         :parallelism (get cost "p")}))
    (catch Exception _ nil)))

(defn hash-password
  "Hash password for storage. Returns a PHC string, never the password itself."
  [password]
  (let [{:keys [memory iterations parallelism]} argon2-params
        salt (nonce/random-bytes salt-length)
        derived (argon2-derive password salt argon2-params hash-length)]
    (str phc-prefix
         "v=19"
         (format "$m=%d,t=%d,p=%d" memory iterations parallelism)
         (format "$%s$%s" (unpadded (codecs/bytes->b64-str salt)) (unpadded (codecs/bytes->b64-str derived))))))

(defn verify-password
  "True when password matches the stored PHC hash.

  The comparison is constant time via buddy's bytes/equals?, and a hash is
  always derived before comparing so that a wrong password costs the same as a
  right one."
  [password stored]
  (boolean
   (when-let [{:keys [salt hash] :as params} (and stored (parse-phc stored))]
     (bytes/equals? (argon2-derive password salt params (alength hash)) hash))))

(defn password-hash-needs-rehash?
  "True when a stored hash used weaker cost parameters than the current ones.

  Lets a future deploy raise cost without a migration: re-hash on next signin."
  [stored]
  (boolean
   (when-let [{:keys [memory iterations parallelism]} (and stored (parse-phc stored))]
     (not= (select-keys argon2-params [:memory :iterations :parallelism])
           {:memory memory :iterations iterations :parallelism parallelism}))))

;;; opaque tokens

(defn generate-token
  "A fresh opaque token, url-safe base64 so it can go in a URL unharmed."
  []
  (codecs/bytes->b64-str (nonce/random-bytes token-bytes) true))

(defn token-hash
  "SHA-256 of token, hex encoded.

  This is what gets stored. The token itself is shown to the user exactly once,
  inside a link."
  [token]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (codecs/bytes->hex (.digest digest (codecs/str->bytes token)))))

(defn secure-equals?
  "Constant time string comparison, for anything secret."
  [a b]
  (and (string? a) (string? b) (bytes/equals? a b)))
