(ns modules.products.handlers
  "HTTP handlers for a vendor's products.

  Thin, like modules.auth.handlers: resolve the vendor the session belongs to,
  hand the submitted values to modules.products.service, and turn the result
  into a fragment for htmx or a page/redirect for a plain request.

  Two shapes of form reply live here. A create or edit loads its form into the
  modal over htmx, so a failure re-renders the modal and a success leaves the
  modal empty while swapping the grid out of band. Reached directly, the same
  endpoints render a standalone page with a plain form, so the feature does not
  require scripting.

  The route guards have already confirmed the session is a signed-in vendor and
  the anti-forgery token is valid. What is left here is the verification rule
  and the shape of each reply. Every lookup is scoped to the session's vendor
  id, so a product id belonging to another vendor is simply not found."
  (:require
   [modules.products.service :as service]
   [modules.vendors.db :as vendors.db]
   [ring.util.http-response :as http-response]
   [views.dashboard.products :as views]))

(defn- db [request]
  (get-in request [:system :db]))

(defn- session [request]
  (:session request))

(defn- htmx? [request]
  (= "true" (get-in request [:headers "hx-request"])))

(defn- verified? [request]
  (:verified? (session request)))

(defn- vendor-id
  "The vendor profile the signed-in account owns, or nil when there is none.

  The guards confirm account_type is :vendor but not that a profile row exists;
  a nil here is a broken invariant, handled as a 404 rather than an exception."
  [request]
  (some-> (vendors.db/find-vendor-by-account-id
           (db request) (get-in request [:session :account-id]))
          :id))

(defn- products-for [request]
  (if-let [vid (vendor-id request)]
    (service/list-products (db request) vid)
    []))

(def ^:private product-fields
  [:name :description :price :currency :billing-intervals :category :image-url])

(defn- submitted
  "The form values, ready to be validated or echoed back. The interval
  checkboxes arrive as an absent key, a string, or a vector."
  [request]
  (select-keys (:params request) product-fields))

(defn- product-id
  "The :id path segment as a number, or nil when it is not one. Reitit hands it
  over as a string."
  [request]
  (try
    (Long/parseLong (str (get-in request [:path-params :id])))
    (catch NumberFormatException _ nil)))

(defn- list-opts [request opts]
  (assoc opts
         :verified? (verified? request)
         :products  (products-for request)))

(defn- respond-form
  "Render a form failure where the form lives: the modal for htmx, the
  standalone page otherwise."
  [request opts state]
  (let [opts (merge opts state)]
    (if (htmx? request)
      (views/modal-fragment opts)
      (views/form-page (assoc opts :heading (:title opts))))))

(defn list-products
  "GET /vendor/products. Always a full page: hx-boost swaps the body with it."
  [request]
  (views/products-page (list-opts request {})))

(defn new-product
  "GET /vendor/products/new. A modal fragment for htmx, a standalone page
  otherwise."
  [request]
  (let [opts {:action       "/vendor/products"
              :submit-label "Add product"
              :title        "New product"}]
    (if (htmx? request)
      (views/modal-fragment opts)
      (views/form-page (assoc opts :heading "New product")))))

(defn create-product
  "POST /vendor/products."
  [request]
  (let [input (submitted request)
        vid   (vendor-id request)
        opts  {:action       "/vendor/products"
               :submit-label "Add product"
               :title        "New product"}]
    (cond
      (nil? vid)
      (http-response/not-found)

      (not (verified? request))
      (respond-form request opts
                    {:values     input
                     :form-error "Verify your email address before listing a product."})

      :else
      (let [result (service/create-product! (db request) vid input)]
        (if (:ok? result)
          (if (htmx? request)
            (views/section-oob-fragment (list-opts request {:toast "Product added."}))
            (http-response/see-other "/vendor/products"))
          (respond-form request opts {:values input :errors (:errors result)}))))))

(defn- edit-opts
  [pid values]
  {:action       (str "/vendor/products/" pid)
   :submit-label "Save changes"
   :title        "Edit product"
   :values       values})

(defn edit-product
  "GET /vendor/products/:id/edit. A modal fragment for htmx, a standalone page
  otherwise."
  [request]
  (let [pid (product-id request)
        vid (vendor-id request)]
    (if-let [product (and pid vid (service/find-product (db request) vid pid))]
      (let [opts (edit-opts pid (views/product->values product))]
        (if (htmx? request)
          (views/modal-fragment opts)
          (views/form-page (assoc opts :heading "Edit product"))))
      (http-response/not-found))))

(defn update-product
  "POST /vendor/products/:id."
  [request]
  (let [pid   (product-id request)
        vid   (vendor-id request)
        input (submitted request)]
    (cond
      (or (nil? pid) (nil? vid))
      (http-response/not-found)

      (not (verified? request))
      (respond-form request (edit-opts pid input)
                    {:values     input
                     :form-error "Verify your email address before changing products."})

      :else
      (let [result (service/update-product! (db request) vid pid input)]
        (cond
          (:ok? result)
          (if (htmx? request)
            (views/section-oob-fragment (list-opts request {:toast "Product saved."}))
            (http-response/see-other "/vendor/products"))

          (= :not-found (:reason result))
          (http-response/not-found)

          :else
          (respond-form request (edit-opts pid input)
                        {:values input :errors (:errors result)}))))))

(defn set-product-active
  "POST /vendor/products/:id/active. The form carries the state to move to (:active
  \"true\" or \"false\") rather than asking the server to flip whatever it finds,
  so a retried request lands on the same state."
  [request]
  (let [pid     (product-id request)
        vid     (vendor-id request)
        active? (= "true" (get-in request [:params :active]))]
    (cond
      (or (nil? pid) (nil? vid))
      (http-response/not-found)

      (not (verified? request))
      (let [opts (list-opts request
                            {:error "Verify your email address before changing products."})]
        (if (htmx? request)
          (views/section-fragment opts)
          (views/products-page opts)))

      :else
      (if (:ok? (service/set-product-active! (db request) vid pid active?))
        (if (htmx? request)
          (views/section-fragment
           (list-opts request
                      {:toast (if active? "Product activated." "Product deactivated.")}))
          (http-response/see-other "/vendor/products"))
        (http-response/not-found)))))

(defn delete-product
  "POST /vendor/products/:id/delete. Delete is a POST because an HTML form
  cannot send DELETE."
  [request]
  (let [pid (product-id request)
        vid (vendor-id request)]
    (cond
      (or (nil? pid) (nil? vid))
      (http-response/not-found)

      (not (verified? request))
      (let [opts (list-opts request
                            {:error "Verify your email address before changing products."})]
        (if (htmx? request)
          (views/section-fragment opts)
          (views/products-page opts)))

      :else
      (if (:ok? (service/delete-product! (db request) vid pid))
        (if (htmx? request)
          (views/section-fragment (list-opts request {:toast "Product deleted."}))
          (http-response/see-other "/vendor/products"))
        (http-response/not-found)))))
