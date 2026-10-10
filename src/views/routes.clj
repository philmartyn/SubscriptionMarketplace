(ns views.routes
  (:require
   [integrant.core :as ig]
   [modules.auth.handlers :as auth.handlers]
   [modules.auth.middleware :as auth.middleware]
   [modules.products.handlers :as products.handlers]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [shared.middleware.exception :as exception]
   [shared.middleware.formats :as formats]
   [views.auth :as auth]
   [views.dashboard.home :as home]
   [views.dashboard.vendor :as vendor-dashboard]
   [views.landing.index :as landing]))

;; Routes
(defn ui-routes [_opts]
  (let [vendor-guard [auth.middleware/wrap-auth-required
                      ;; First entry is outermost: anonymous requests are
                      ;; rejected before an account type is ever read.
                      [auth.middleware/wrap-account-type
                       {:account-type :vendor
                        :redirect     "/dashboard"}]]]
    [["/" {:get landing/landing}]
     ["/auth/signin" {:get  auth/signin
                      :post auth.handlers/signin}]
     ["/auth/signup" {:get  auth/signup
                      :post auth.handlers/signup}]
     ["/auth/vendor-signup" {:get  auth/vendor-signup
                             :post auth.handlers/vendor-signup}]
     ;; The email link's destination. POST accepts the same token for a CSRF
     ;; protected form; GET is what an emailed one-time link actually does.
     ["/auth/verify" {:get  auth.handlers/verify-email
                      :post auth.handlers/verify-email}]
     ["/auth/resend-verification" {:post auth.handlers/resend-verification}]
     ;; Development only: stands in for clicking the emailed link while no real
     ;; mailer is wired. The handler answers 404 unless :dev-verify? is on.
     ["/auth/dev-verify" {:post auth.handlers/verify-now}]
     ["/auth/signout" {:post auth.handlers/signout}]
     ["/dashboard" {:get        home/home
                    :middleware [auth.middleware/wrap-auth-required]}]
     ["/vendor/dashboard"
      {:get        vendor-dashboard/home
       :middleware vendor-guard}]
     ;; The vendor's product listing: a grid of what is listed, plus the POST
     ;; that adds one. Everything here is scoped to the signed-in vendor.
     ["/vendor/products"
      {:get        products.handlers/list-products
       :post       products.handlers/create-product
       :middleware vendor-guard}]
     ;; The create form's endpoint, loaded into the modal. A static segment
     ;; sibling of :id, so reitit needs to be told the overlap is intended; a
     ;; static segment wins, so /new is never read as a product id.
     ["/vendor/products/new"
      {:get        products.handlers/new-product
       :conflicting true
       :middleware vendor-guard}]
     ["/vendor/products/:id/edit"
      {:get        products.handlers/edit-product
       :middleware vendor-guard}]
     ["/vendor/products/:id"
      {:post       products.handlers/update-product
       :conflicting true
       :middleware vendor-guard}]
     ["/vendor/products/:id/delete"
      {:post       products.handlers/delete-product
       :middleware vendor-guard}]
     ["/vendor/products/:id/active"
      {:post       products.handlers/set-product-active
       :middleware vendor-guard}]]))

(def route-data
  {:muuntaja   formats/instance
   :middleware
   [;; Default middleware for ui
    ;; query-params & form-params
    parameters/parameters-middleware
    ;; encoding response body
    muuntaja/format-response-middleware
    ;; exception handling
    exception/wrap-exception]})

(derive :reitit.routes/ui :reitit/routes)

(defmethod ig/init-key :reitit.routes/ui
  [_ {:keys [base-path]
      :or   {base-path ""}
      :as   opts}]
  (fn [] [base-path route-data (ui-routes opts)]))