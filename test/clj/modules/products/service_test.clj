(ns modules.products.service-test
  "The product rules, exercised against a live Postgres.

  Signup is not involved here - a vendor row is written directly - so these
  tests stay about validation, price parsing and vendor scoping."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [modules.auth.db :as auth.db]
   [modules.auth.support :as sup]
   [modules.products.db :as products.db]
   [modules.products.service :as service]
   [modules.vendors.db :as vendors.db]))

(use-fixtures :each sup/with-db)

(defn- make-vendor!
  "An account + vendor profile, and the vendor id.

  Written through modules.auth.db so the profile is created exactly as signup
  would leave it, without pulling in the mailer or password hashing."
  [email]
  (let [account    (auth.db/register! sup/*db*
                                      {:account-type     :vendor
                                       :email            email
                                       :email-normalized (str/lower-case email)
                                       :password-hash    "x"
                                       :name             "Vendor Co"})]
    (:id (vendors.db/find-vendor-by-account-id sup/*db* (:id account)))))

(defn- valid-input
  [overrides]
  (merge {:name              "Weekly vegetable box"
          :description       "A seasonal mix"
          :price             "12.50"
          :currency          "EUR"
          :billing-intervals ["monthly"]
          :category          "Produce"
          :image-url         "https://example.com/box.png"}
         overrides))

;;; price parsing

(deftest prices-parse-to-cents
  (doseq [[text cents] [["0" 0]
                        ["12" 1200]
                        ["12.5" 1250]
                        ["12.50" 1250]
                        [" 9.99 " 999]
                        ["1000000" 100000000]]]
    (is (= cents (service/parse-price-cents text)) (pr-str text))))

(deftest unparseable-prices-return-nil
  (doseq [text ["" "  " "-1" "abc" "12.345" "1,50" "12." ".5" "1e3" "999999999999999999999"]]
    (is (nil? (service/parse-price-cents text)) (pr-str text))))

;;; create

(deftest create-stores-the-submitted-values
  (let [vid    (make-vendor! "vendor@example.com")
        result (service/create-product! sup/*db* vid (valid-input {}))]
    (is (:ok? result))
    (let [product (:product result)]
      (is (= "Weekly vegetable box" (:name product)))
      (is (= "A seasonal mix" (:description product)))
      (is (= 1250 (:price_cents product)))
      (is (= "EUR" (:currency product)))
      (is (= ["monthly"] (:billing_intervals product)))
      (is (= "Produce" (:category product)))
      (is (= "https://example.com/box.png" (:image_url product)))
      (is (true? (:is_active product)))
      (is (= (:id product)
             (:id (first (service/list-products sup/*db* vid))))))))

(deftest blank-optional-fields-become-null
  (let [vid               (make-vendor! "vendor@example.com")
        {:keys [product]} (service/create-product!
                           sup/*db* vid
                           (valid-input {:description "  " :category "" :image-url ""}))]
    (is (nil? (:description product)))
    (is (nil? (:category product)))
    (is (nil? (:image_url product)))))

(deftest a-product-may-offer-several-intervals-or-none
  (let [vid (make-vendor! "vendor@example.com")
        many (:product (service/create-product!
                        sup/*db* vid
                        (valid-input {:billing-intervals ["yearly" "weekly"]})))
        none (:product (service/create-product!
                        sup/*db* vid
                        (valid-input {:name "Customer picks"
                                      :billing-intervals []})))]
    (is (= ["weekly" "yearly"] (:billing_intervals many))
        "stored in a canonical order, not the order submitted")
    (is (= [] (:billing_intervals none))
        "no intervals means the customer chooses the schedule")))

(deftest invalid-input-is-rejected-field-by-field
  (let [vid    (make-vendor! "vendor@example.com")
        result (service/create-product! sup/*db* vid
                                        {:name              "  "
                                         :price             "-5"
                                         :currency          "JPY"
                                         :billing-intervals ["fortnightly"]
                                         :image-url         "not-a-url"})]
    (is (false? (:ok? result)))
    (doseq [field [:name :price :currency :billing-intervals :image-url]]
      (is (contains? (:errors result) field) (str field)))
    (is (empty? (service/list-products sup/*db* vid))
        "a rejected product is not written")))

(deftest a-price-above-the-ceiling-is-rejected
  (let [vid (make-vendor! "vendor@example.com")]
    (is (contains? (:errors (service/create-product!
                             sup/*db* vid (valid-input {:price "1000000.01"})))
                   :price))))

;;; update and delete, scoped to the owner

(deftest a-vendor-can-only-touch-its-own-products
  (let [a                 (make-vendor! "a@example.com")
        b                 (make-vendor! "b@example.com")
        {:keys [product]} (service/create-product! sup/*db* a (valid-input {}))
        pid               (:id product)]
    (testing "the owner can update"
      (let [result (service/update-product! sup/*db* a pid
                                            (valid-input {:name "Renamed"}))]
        (is (:ok? result))
        (is (= "Renamed" (:name (:product result))))))
    (testing "another vendor cannot"
      (is (= :not-found
             (:reason (service/update-product! sup/*db* b pid (valid-input {})))))
      (is (= :not-found (:reason (service/delete-product! sup/*db* b pid))))
      (is (= "Renamed" (:name (service/find-product sup/*db* a pid)))
          "the failed update left the row alone"))
    (testing "the list is scoped too"
      (is (= 1 (count (service/list-products sup/*db* a))))
      (is (empty? (service/list-products sup/*db* b))))
    (testing "the owner can delete"
      (is (:ok? (service/delete-product! sup/*db* a pid)))
      (is (empty? (service/list-products sup/*db* a))))))

(deftest updating-a-missing-product-is-not-found
  (let [vid (make-vendor! "vendor@example.com")]
    (is (= :not-found
           (:reason (service/update-product! sup/*db* vid 999 (valid-input {})))))))

;;; active state, scoped to the owner

(deftest a-vendor-can-activate-and-deactivate-its-own-product
  (let [vid               (make-vendor! "vendor@example.com")
        {:keys [product]} (service/create-product! sup/*db* vid (valid-input {}))
        pid               (:id product)]
    (is (true? (:is_active product)) "a new product starts active")
    (testing "the owner can switch it off, then back on"
      (is (false? (:is_active (:product (service/set-product-active! sup/*db* vid pid false)))))
      (is (true? (:is_active (:product (service/set-product-active! sup/*db* vid pid true))))))
    (testing "and the switch is the stored state"
      (service/set-product-active! sup/*db* vid pid false)
      (is (false? (:is_active (service/find-product sup/*db* vid pid)))))))

(deftest active-state-is-scoped-to-the-owner
  (let [a                 (make-vendor! "a@example.com")
        b                 (make-vendor! "b@example.com")
        {:keys [product]} (service/create-product! sup/*db* a (valid-input {}))
        pid               (:id product)]
    (is (= :not-found (:reason (service/set-product-active! sup/*db* b pid false))))
    (is (true? (:is_active (service/find-product sup/*db* a pid)))
        "another vendor's attempt leaves the row alone")))

(deftest setting-active-on-a-missing-product-is-not-found
  (let [vid (make-vendor! "vendor@example.com")]
    (is (= :not-found (:reason (service/set-product-active! sup/*db* vid 999 false))))))
