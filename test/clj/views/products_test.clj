(ns views.products-test
  "The product management page: the grid, and the modal and standalone forms."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ring.middleware.anti-forgery :as anti-forgery]
   [views.dashboard.products :as products]))

(defn- body [resp]
  (:body resp))

(def sample-product
  {:id                1
   :name              "Weekly vegetable box"
   :description       "A seasonal mix"
   :price_cents       1250
   :currency          "EUR"
   :billing_intervals ["monthly"]
   :category          "Produce"
   :image_url         "https://example.com/box.png"
   :is_active         true})

;;; formatting

(deftest prices-format-to-two-decimals
  (is (= "12.50" (products/cents->decimal 1250)))
  (is (= "0.00" (products/cents->decimal 0)))
  (is (= "12.50 EUR" (products/format-price sample-product))))

;;; the grid

(deftest the-list-renders-a-grid-of-products
  (let [empty-html (body (products/products-page {:verified? true :products []}))
        one-html   (body (products/products-page {:verified? true :products [sample-product]}))]
    (is (str/includes? empty-html "You have not listed any products yet."))
    (is (str/includes? one-html "Weekly vegetable box"))
    (is (str/includes? one-html "12.50 EUR"))
    (is (str/includes? one-html "Monthly"))
    (is (str/includes? one-html "/vendor/products/1/edit"))
    (is (str/includes? one-html "/vendor/products/1/delete"))))

(deftest the-grid-is-the-grid-layout-with-the-add-button-underneath
  (let [html (body (products/products-page {:verified? true :products [sample-product]}))
        grid (str/index-of html "grid-cols")
        add  (str/index-of html "Add a product")]
    (is (some? grid) "the products are laid out in a responsive grid")
    (is (some? add))
    (is (< grid add) "the add button comes after the grid")))

(deftest a-product-with-no-intervals-lets-the-customer-choose
  (let [html (body (products/products-page
                    {:verified? true
                     :products  [(assoc sample-product :billing_intervals [])]}))]
    (is (str/includes? html "Customer chooses"))))

(deftest an-inactive-product-is-marked-inactive
  (let [html (body (products/products-page
                    {:verified? true
                     :products  [(assoc sample-product :is_active false)]}))]
    (is (str/includes? html "Inactive"))))

(deftest an-active-product-offers-deactivation
  (let [html (body (products/products-page {:verified? true :products [sample-product]}))]
    (is (str/includes? html "/vendor/products/1/active"))
    (is (str/includes? html "Deactivate"))
    (is (str/includes? html "name=\"active\""))
    (is (str/includes? html "value=\"false\"")
        "the form carries the state to move to, not a request to flip")))

(deftest an-inactive-product-offers-activation
  (let [html (body (products/products-page
                    {:verified? true
                     :products  [(assoc sample-product :is_active false)]}))]
    (is (str/includes? html "Activate"))
    (is (str/includes? html "value=\"true\""))))

(deftest an-unverified-vendor-sees-the-note-and-no-add-button
  (let [html (body (products/products-page {:verified? false :products []}))]
    (is (str/includes? html "Verify your email address before listing a product."))
    (is (not (str/includes? html "Add a product"))
        "there is no point offering a button whose submit is refused")))

;;; the forms

(deftest the-standalone-form-is-a-plain-post
  (let [html (body (products/form-page {:heading      "New product"
                                        :title        "New product"
                                        :action       "/vendor/products"
                                        :submit-label "Add product"
                                        :values       {}}))]
    (is (str/includes? html "action=\"/vendor/products\""))
    (is (not (str/includes? html "hx-post"))
        "without htmx the form posts and redirects")
    (doseq [field ["name" "description" "price" "currency" "category" "image-url"]]
      (testing field
        (is (str/includes? html (str "name=\"" field "\"")))))
    (is (str/includes? html "name=\"billing-intervals\"")
        "the intervals post under one name, once per ticked box")))

(deftest the-modal-form-targets-the-modal
  (let [html (body (products/modal-fragment {:title        "New product"
                                             :action       "/vendor/products"
                                             :submit-label "Add product"
                                             :values       {}}))]
    (is (str/includes? html "role=\"dialog\""))
    (is (str/includes? html "hx-post=\"/vendor/products\""))
    (is (str/includes? html "hx-target=\"#modal\""))
    (is (str/includes? html "name=\"billing-intervals\""))))

(deftest the-edit-form-prefills-the-product
  (let [html (body (products/form-page
                    {:heading      "Edit product"
                     :title        "Edit product"
                     :action       "/vendor/products/1"
                     :submit-label "Save changes"
                     :values       (products/product->values sample-product)}))]
    (is (str/includes? html "value=\"Weekly vegetable box\""))
    (is (str/includes? html "value=\"12.50\""))
    (is (str/includes? html "value=\"https://example.com/box.png\""))
    (is (str/includes? html "action=\"/vendor/products/1\""))
    (is (str/includes? html "checked")
        "the stored interval shows as a ticked box")))

;;; intervals

(deftest selected-intervals-collapses-what-the-form-sends
  (is (= #{} (products/selected-intervals {})))
  (is (= #{"monthly"} (products/selected-intervals {:billing-intervals "monthly"})))
  (is (= #{"weekly" "monthly"}
         (products/selected-intervals {:billing-intervals ["weekly" "monthly"]}))))

(deftest product-values-round-trip-the-intervals
  (is (= ["monthly"] (:billing-intervals (products/product->values sample-product)))))

;;; csrf

(deftest the-forms-carry-a-csrf-token-when-one-is-bound
  (binding [anti-forgery/*anti-forgery-token* "page-token"]
    (is (str/includes? (body (products/products-page
                              {:verified? true :products [sample-product]}))
                       "value=\"page-token\"")
        "the delete form")
    (is (str/includes? (body (products/form-page
                              {:heading      "New product"
                               :title        "New product"
                               :action       "/vendor/products"
                               :submit-label "Add product"
                               :values       {}}))
                       "value=\"page-token\"")))
  (is (not (str/includes? (body (products/products-page {:verified? true :products []}))
                          "__anti-forgery-token"))
      "outside a request no token is bound, and no input is rendered"))
