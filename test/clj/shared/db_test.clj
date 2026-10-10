(ns shared.db-test
  "The shared SQL helpers, exercised against a live Postgres.

  The transaction case is the one worth pinning directly: a transaction handle
  does not inherit the datasource's builder-fn, so a row read through these
  helpers still has to come back with unqualified lower-case keys."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [honey.sql :as sql]
   [modules.auth.support :as sup]
   [next.jdbc :as jdbc]
   [shared.db :as db]))

(use-fixtures :each sup/with-db)

(defn- account-values [email]
  {:email            email
   :email_normalized email
   :password_hash    "not-a-real-hash"
   :account_type     "user"})

(defn- insert-account
  [email]
  (db/one sup/*db*
          (sql/format {:insert-into :accounts
                       :values      [(account-values email)]
                       :returning   [:*]})))

(deftest one-returns-a-row-with-unqualified-keys
  (let [row (insert-account "one@example.com")]
    (is (= "one@example.com" (:email row)))
    (is (some? (:id row)))
    (is (nil? (get row :accounts/id))
        "keys are not namespaced after the table")))

(deftest one-returns-nil-when-nothing-matches
  (is (nil? (db/one sup/*db*
                    (sql/format {:select [:*]
                                 :from   [:accounts]
                                 :where  [:= :id 999999]})))))

(deftest one-inside-a-transaction-still-unqualifies-keys
  (let [row (jdbc/with-transaction [tx sup/*db*]
              (db/one tx
                      (sql/format {:insert-into :accounts
                                   :values      [(account-values "tx@example.com")]
                                   :returning   [:*]})))]
    (is (= "tx@example.com" (:email row)))
    (is (some? (:id row))
        "the builder-fn is applied to the transaction handle too")))

(deftest many-returns-every-row
  (insert-account "a@example.com")
  (insert-account "b@example.com")
  (let [rows (db/many sup/*db*
                      (sql/format {:select [:*]
                                   :from   [:accounts]
                                   :order-by [[:id :asc]]}))]
    (is (= ["a@example.com" "b@example.com"] (mapv :email rows)))))

(deftest affected-counts-the-rows-a-statement-changes
  (let [{:keys [id]} (insert-account "c@example.com")
        update!      (fn [where]
                       (db/affected sup/*db*
                                    (sql/format {:update :accounts
                                                 :set    {:account_type "vendor"}
                                                 :where  where})))]
    (testing "a matching statement reports the rows it changed"
      (is (= 1 (update! [:= :id id]))))
    (testing "a statement that matches nothing reports zero"
      (is (= 0 (update! [:= :id 999999]))))))

(deftest coercion-helpers-return-driver-friendly-values
  (testing "->ts converts an Instant to a java.sql.Timestamp"
    (let [instant (java.time.Instant/parse "2020-01-02T03:04:05Z")
          ts      (db/->ts instant)]
      (is (instance? java.sql.Timestamp ts))
      (is (= instant (.toInstant ts)))))
  (testing "now-ts is a Timestamp and expiry is an Instant in the future"
    (is (instance? java.sql.Timestamp (db/now-ts)))
    (let [expires (db/expiry 1)]
      (is (instance? java.time.Instant expires))
      (is (pos? (.compareTo expires (java.time.Instant/now)))))))
