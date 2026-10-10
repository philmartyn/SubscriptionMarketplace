(ns modules.auth.support
  "Postgres fixture shared by the auth database tests.

  There is no test in this project that can run without a live Postgres, and
  this namespace does not introduce that requirement: core-test boots
  app.core, which initialises :database.sql/connection, and the :test profile
  sets :migrate? true. A machine with no database fails there first. What this
  does is stop each namespace from inventing its own credentials and its own
  idea of when the schema is ready."
  (:require
   [clojure.string :as str]
   [components.db.core :as db.core]
   [components.db.migrations :as migrations]
   [modules.auth.crypto :as crypto]
   [next.jdbc :as jdbc]
   [shared.config :as config]))

(def fast-argon2-params
  "Cost low enough that a fixture signing up a handful of accounts stays quick.

  The shipped baseline is asserted in crypto-test against a copy of the var
  taken at load time, so rebinding it here cannot let a regression slip past."
  {:memory 256 :iterations 1 :parallelism 1})

(def ^:private auth-tables
  "Accounts owns the profile rows, so truncating it cascades to users, vendors
  and products anyway. products is listed explicitly so its identity is reset
  too, which is what lets a test assert on a product id."
  ["accounts" "users" "vendors" "products" "sessions" "verification_tokens"])

(def ^:private db-spec
  (delay (get-in (config/system-config {:profile :test})
                 [:database.sql/connection])))

;; Migrated once per JVM. The migrations themselves are idempotent, but
;; re-checking the whole index before every test would be pure overhead.
(defonce ^:private migrated
  (delay (let [ds (db.core/datastore (dissoc @db-spec :migrate?))]
           (migrations/migrate! ds)
           ds)))

(defn datastore
  "The shared datasource, with the schema applied."
  []
  @migrated)

(defn wipe!
  "Empty the auth tables and restart their identities.

  CASCADE because accounts owns the profile rows, and RESTART IDENTITY because
  a test that asserts on ids is only meaningful if the ids are predictable."
  [ds]
  (jdbc/execute! ds [(str "TRUNCATE " (str/join ", " auth-tables)
                          " RESTART IDENTITY CASCADE")]))

(def ^:dynamic *db*
  "The datasource under test, bound by the with-db fixture.")

(defn with-db
  "Fixture: an empty, migrated schema before and after each test.

  Tables are cleared on the way out as well as on the way in, so a failing
  assertion does not leave rows behind for the next test to trip over."
  [f]
  (let [ds (datastore)]
    (wipe! ds)
    (binding [*db* ds]
      (try
        (f)
        (finally
          (wipe! ds))))))

(defn with-fast-argon2
  "Fixture: rebind cost so tests that hash a password do not pay real cost.

  Needed because register! hashes inline. Leaving the OWASP baseline in place
  would add roughly a seventh of a second to every signup assertion."
  [f]
  (with-redefs [crypto/argon2-params fast-argon2-params]
    (f)))
