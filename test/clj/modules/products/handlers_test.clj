(ns modules.products.handlers-test
  "The product HTTP handlers, exercised without a running server.

  Each handler gets the request shape wrap-base and reitit deliver: keywordized
  params, path params under :path-params, a session map, and the database under
  :system. The route guards are not in the path here - these tests hold the
  session shape the guards would have already accepted, and cover the rules the
  handlers own."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [modules.auth.db :as auth.db]
   [modules.auth.support :as sup]
   [modules.products.db :as products.db]
   [modules.products.handlers :as handlers]
   [modules.vendors.db :as vendors.db]))

(use-fixtures :each sup/with-db)

(defn- make-vendor!
  "A verified vendor account + profile, and the ids the session would carry."
  [email]
  (let [account    (auth.db/register! sup/*db*
                                      {:account-type     :vendor
                                       :email            email
                                       :email-normalized (str/lower-case email)
                                       :password-hash    "x"
                                       :name             "Vendor Co"})
        account-id (:id account)]
    (auth.db/mark-email-verified! sup/*db* account-id)
    {:account-id account-id
     :vendor-id  (:id (vendors.db/find-vendor-by-account-id sup/*db* account-id))}))

(defn- request
  [method path {:keys [params path-params account-id verified? htmx?]
                :or   {verified? true}}]
  {:request-method method
   :uri            path
   :params         (or params {})
   :path-params    path-params
   :session        {:account-id   account-id
                    :account-type :vendor
                    :verified?    verified?}
   :headers        (when htmx? {"hx-request" "true"})
   :system         {:db sup/*db*}})

(defn- insert-product!
  "A product row written straight to the database."
  [vendor-id name]
  (products.db/insert-product! sup/*db*
                               {:vendor-id        vendor-id
                                :name             name
                                :price-cents      1250
                                :currency         "EUR"
                                :billing-intervals ["monthly"]}))

(defn- valid-params
  [overrides]
  (merge {:name              "Weekly box"
          :price             "12.50"
          :currency          "EUR"
          :billing-intervals ["monthly"]}
         overrides))

;;; listing

(deftest the-list-shows-only-this-vendors-products
  (let [{:keys [account-id vendor-id]} (make-vendor! "a@example.com")
        {other-vendor :vendor-id}     (make-vendor! "b@example.com")
        _                             (insert-product! vendor-id "Mine")
        _                             (insert-product! other-vendor "Theirs")
        resp                          (handlers/list-products
                                       (request :get "/vendor/products" {:account-id account-id}))
        html                          (:body resp)]
    (is (= 200 (:status resp)))
    (is (str/includes? html "Mine"))
    (is (not (str/includes? html "Theirs"))
        "another vendor's product never appears")))

(deftest the-list-is-a-whole-page
  (let [{:keys [account-id]} (make-vendor! "a@example.com")
        resp (handlers/list-products (request :get "/vendor/products" {:account-id account-id}))]
    (is (re-find #"(?i)<!doctype html>" (:body resp)))
    (is (str/includes? (:body resp) "You have not listed any products yet."))))

;;; create

(deftest create-from-htmx-saves-and-swaps-in-the-section
  (let [{:keys [account-id vendor-id]} (make-vendor! "vendor@example.com")
        resp (handlers/create-product
              (request :post "/vendor/products"
                       {:account-id account-id :htmx? true
                        :params     (valid-params {})}))
        html (:body resp)]
    (is (= 200 (:status resp)))
    (is (not (re-find #"(?i)<!doctype html>" html)) "htmx gets a fragment")
    (is (str/includes? html "Product added."))
    (is (str/includes? html "Weekly box"))
    (is (str/includes? html "hx-swap-oob")
        "the grid is swapped out of band, leaving the modal to close")
    (is (str/includes? html "beforeend:#toasts")
        "the confirmation arrives as a toast, not an in-page banner")
    (is (= 1 (count (products.db/list-products-for-vendor sup/*db* vendor-id))))))

(deftest create-stores-every-selected-interval
  (let [{:keys [account-id vendor-id]} (make-vendor! "vendor@example.com")
        _    (handlers/create-product
              (request :post "/vendor/products"
                       {:account-id account-id :htmx? true
                        :params     (valid-params {:billing-intervals ["monthly" "weekly"]})}))
        product (first (products.db/list-products-for-vendor sup/*db* vendor-id))]
    (is (= ["weekly" "monthly"] (:billing_intervals product)))))

;;; the modal

(deftest the-new-product-modal-is-a-fragment-for-htmx
  (let [{:keys [account-id]} (make-vendor! "vendor@example.com")
        resp (handlers/new-product
              (request :get "/vendor/products/new"
                       {:account-id account-id :htmx? true}))
        html (:body resp)]
    (is (= 200 (:status resp)))
    (is (not (re-find #"(?i)<!doctype html>" html)) "htmx gets just the modal")
    (is (str/includes? html "action=\"/vendor/products\""))
    (is (str/includes? html "hx-target=\"#modal\""))
    (is (str/includes? html "name=\"billing-intervals\""))))

(deftest the-new-product-page-works-without-scripting
  (let [{:keys [account-id]} (make-vendor! "vendor@example.com")
        resp (handlers/new-product
              (request :get "/vendor/products/new" {:account-id account-id}))
        html (:body resp)]
    (is (= 200 (:status resp)))
    (is (re-find #"(?i)<!doctype html>" html) "a plain request gets a whole page")
    (is (str/includes? html "action=\"/vendor/products\""))
    (is (not (str/includes? html "hx-target=\"#modal\""))
        "the standalone form posts normally rather than into a modal")))

(deftest create-from-a-plain-post-redirects
  (let [{:keys [account-id vendor-id]} (make-vendor! "vendor@example.com")
        resp (handlers/create-product
              (request :post "/vendor/products"
                       {:account-id account-id :params (valid-params {})}))]
    (is (= 303 (:status resp)))
    (is (= "/vendor/products" (get-in resp [:headers "Location"])))
    (is (= 1 (count (products.db/list-products-for-vendor sup/*db* vendor-id))))))

(deftest invalid-input-keeps-the-values-and-shows-errors
  (let [{:keys [account-id vendor-id]} (make-vendor! "vendor@example.com")
        resp (handlers/create-product
              (request :post "/vendor/products"
                       {:account-id account-id :htmx? true
                        :params     (valid-params {:name "" :price "abc"})}))
        html (:body resp)]
    (is (= 200 (:status resp)))
    (is (str/includes? html "Give the product a name."))
    (is (str/includes? html "Enter a price like 12.50"))
    (is (empty? (products.db/list-products-for-vendor sup/*db* vendor-id))
        "nothing is written when validation fails")))

(deftest an-unverified-vendor-cannot-list-a-product
  (let [{:keys [account-id vendor-id]} (make-vendor! "vendor@example.com")
        resp (handlers/create-product
              (request :post "/vendor/products"
                       {:account-id account-id :verified? false :htmx? true
                        :params     (valid-params {})}))]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "Verify your email address"))
    (is (empty? (products.db/list-products-for-vendor sup/*db* vendor-id)))))

;;; edit and update

(deftest the-edit-page-prefills-the-product
  (let [{:keys [account-id vendor-id]} (make-vendor! "vendor@example.com")
        product (insert-product! vendor-id "Weekly box")
        resp    (handlers/edit-product
                 (request :get (str "/vendor/products/" (:id product) "/edit")
                          {:account-id  account-id
                           :path-params {:id (str (:id product))}}))
        html    (:body resp)]
    (is (= 200 (:status resp)))
    (is (re-find #"(?i)<!doctype html>" html))
    (is (str/includes? html "value=\"Weekly box\""))
    (is (str/includes? html "value=\"12.50\""))
    (is (str/includes? html (str "action=\"/vendor/products/" (:id product) "\"")))))

(deftest the-edit-modal-prefills-the-product-for-htmx
  (let [{:keys [account-id vendor-id]} (make-vendor! "vendor@example.com")
        product (insert-product! vendor-id "Weekly box")
        resp    (handlers/edit-product
                 (request :get (str "/vendor/products/" (:id product) "/edit")
                          {:account-id  account-id
                           :htmx?       true
                           :path-params {:id (str (:id product))}}))
        html    (:body resp)]
    (is (= 200 (:status resp)))
    (is (not (re-find #"(?i)<!doctype html>" html)))
    (is (str/includes? html "value=\"Weekly box\""))
    (is (str/includes? html "checked")
        "the stored interval shows as a ticked box")
    (is (str/includes? html "hx-target=\"#modal\""))))

(deftest update-changes-the-product
  (let [{:keys [account-id vendor-id]} (make-vendor! "vendor@example.com")
        product (insert-product! vendor-id "Weekly box")
        pid     (:id product)
        resp    (handlers/update-product
                 (request :post (str "/vendor/products/" pid)
                          {:account-id  account-id
                           :path-params {:id (str pid)}
                           :params      (valid-params {:name "Bigger box" :price "20.00"})}))]
    (is (= 303 (:status resp)))
    (is (= "Bigger box" (:name (products.db/find-product-for-vendor sup/*db* vendor-id pid))))
    (is (= 2000 (:price_cents (products.db/find-product-for-vendor sup/*db* vendor-id pid))))))

(deftest update-from-htmx-swaps-the-section-and-closes-the-modal
  (let [{:keys [account-id vendor-id]} (make-vendor! "vendor@example.com")
        product (insert-product! vendor-id "Weekly box")
        pid     (:id product)
        resp    (handlers/update-product
                 (request :post (str "/vendor/products/" pid)
                          {:account-id  account-id :htmx? true
                           :path-params {:id (str pid)}
                           :params      (valid-params {:name "Bigger box"})}))
        html    (:body resp)]
    (is (= 200 (:status resp)))
    (is (str/includes? html "Product saved."))
    (is (str/includes? html "hx-swap-oob"))
    (is (str/includes? html "Bigger box"))))

(deftest a-failed-update-re-renders-the-form-with-the-errors
  (let [{:keys [account-id vendor-id]} (make-vendor! "vendor@example.com")
        product (insert-product! vendor-id "Weekly box")
        pid     (:id product)
        resp    (handlers/update-product
                 (request :post (str "/vendor/products/" pid)
                          {:account-id  account-id :htmx? true
                           :path-params {:id (str pid)}
                           :params      (valid-params {:name ""})}))
        html    (:body resp)]
    (is (= 200 (:status resp)))
    (is (str/includes? html "Give the product a name."))
    (is (= "Weekly box" (:name (products.db/find-product-for-vendor sup/*db* vendor-id pid)))
        "the stored row is untouched")))

(deftest another-vendors-product-is-not-found
  (let [{:keys [vendor-id]}      (make-vendor! "a@example.com")
        {account-id :account-id} (make-vendor! "b@example.com")
        product                  (insert-product! vendor-id "Weekly box")
        pid                      (:id product)
        path                     (str "/vendor/products/" pid)]
    (testing "edit"
      (is (= 404 (:status (handlers/edit-product
                           (request :get (str path "/edit")
                                    {:account-id account-id :path-params {:id (str pid)}}))))))
    (testing "update"
      (is (= 404 (:status (handlers/update-product
                           (request :post path
                                    {:account-id account-id :path-params {:id (str pid)}
                                     :params (valid-params {})}))))))
    (testing "delete"
      (is (= 404 (:status (handlers/delete-product
                           (request :post (str path "/delete")
                                    {:account-id account-id :path-params {:id (str pid)}})))))
      (is (= "Weekly box" (:name (products.db/find-product-for-vendor sup/*db* vendor-id pid)))))
    (testing "set active"
      (is (= 404 (:status (handlers/set-product-active
                           (request :post (str path "/active")
                                    {:account-id  account-id
                                     :path-params {:id (str pid)}
                                     :params      {:active "false"}})))))
      (is (true? (:is_active (products.db/find-product-for-vendor sup/*db* vendor-id pid)))
          "another vendor's product keeps its state"))))

(deftest a-non-numeric-id-is-not-found
  (let [{:keys [account-id]} (make-vendor! "vendor@example.com")]
    (is (= 404 (:status (handlers/edit-product
                         (request :get "/vendor/products/nope/edit"
                                  {:account-id account-id :path-params {:id "nope"}})))))))

;;; delete

(deftest delete-removes-the-product-and-refreshes-the-list
  (let [{:keys [account-id vendor-id]} (make-vendor! "vendor@example.com")
        product (insert-product! vendor-id "Weekly box")
        pid     (:id product)
        resp    (handlers/delete-product
                 (request :post (str "/vendor/products/" pid "/delete")
                          {:account-id account-id :htmx? true
                           :path-params {:id (str pid)}}))]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "Product deleted."))
    (is (str/includes? (:body resp) "beforeend:#toasts")
        "the confirmation is a toast, not a full-width banner")
    (is (empty? (products.db/list-products-for-vendor sup/*db* vendor-id)))))

(deftest delete-from-a-plain-post-redirects
  (let [{:keys [account-id vendor-id]} (make-vendor! "vendor@example.com")
        product (insert-product! vendor-id "Weekly box")
        pid     (:id product)
        resp    (handlers/delete-product
                 (request :post (str "/vendor/products/" pid "/delete")
                          {:account-id account-id :path-params {:id (str pid)}}))]
    (is (= 303 (:status resp)))
    (is (= "/vendor/products" (get-in resp [:headers "Location"])))
    (is (empty? (products.db/list-products-for-vendor sup/*db* vendor-id)))))

;;; active / inactive

(deftest deactivating-marks-the-product-inactive-and-confirms
  (let [{:keys [account-id vendor-id]} (make-vendor! "vendor@example.com")
        product (insert-product! vendor-id "Weekly box")
        pid     (:id product)
        resp    (handlers/set-product-active
                 (request :post (str "/vendor/products/" pid "/active")
                          {:account-id  account-id
                           :htmx?       true
                           :path-params {:id (str pid)}
                           :params      {:active "false"}}))]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "Product deactivated."))
    (is (str/includes? (:body resp) "beforeend:#toasts")
        "the confirmation is a toast, not a full-width banner")
    (is (false? (:is_active (products.db/find-product-for-vendor sup/*db* vendor-id pid))))))

(deftest activating-again-turns-it-back-on
  (let [{:keys [account-id vendor-id]} (make-vendor! "vendor@example.com")
        product (insert-product! vendor-id "Weekly box")
        pid     (:id product)
        req     (fn [active]
                  (handlers/set-product-active
                   (request :post (str "/vendor/products/" pid "/active")
                            {:account-id  account-id
                             :htmx?       true
                             :path-params {:id (str pid)}
                             :params      {:active active}})))]
    (req "false")
    (let [resp (req "true")]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "Product activated.")))
    (is (true? (:is_active (products.db/find-product-for-vendor sup/*db* vendor-id pid))))))

(deftest setting-active-from-a-plain-post-redirects
  (let [{:keys [account-id vendor-id]} (make-vendor! "vendor@example.com")
        product (insert-product! vendor-id "Weekly box")
        pid     (:id product)
        resp    (handlers/set-product-active
                 (request :post (str "/vendor/products/" pid "/active")
                          {:account-id  account-id
                           :path-params {:id (str pid)}
                           :params      {:active "false"}}))]
    (is (= 303 (:status resp)))
    (is (= "/vendor/products" (get-in resp [:headers "Location"])))
    (is (false? (:is_active (products.db/find-product-for-vendor sup/*db* vendor-id pid))))))

(deftest an-unverified-vendor-cannot-change-active-state
  (let [{:keys [account-id vendor-id]} (make-vendor! "vendor@example.com")
        product (insert-product! vendor-id "Weekly box")
        pid     (:id product)
        resp    (handlers/set-product-active
                 (request :post (str "/vendor/products/" pid "/active")
                          {:account-id  account-id
                           :verified?   false
                           :htmx?       true
                           :path-params {:id (str pid)}
                           :params      {:active "false"}}))]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "Verify your email address"))
    (is (true? (:is_active (products.db/find-product-for-vendor sup/*db* vendor-id pid))))))
