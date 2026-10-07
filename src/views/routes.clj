(ns views.routes
  (:require
   [integrant.core :as ig]
   [modules.auth.handlers :as auth.handlers]
   [modules.auth.middleware :as auth.middleware]
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
     :middleware [auth.middleware/wrap-auth-required
                  ;; First entry is outermost: anonymous requests are rejected
                  ;; before an account type is ever read.
                  [auth.middleware/wrap-account-type
                   {:account-type :vendor
                    :redirect     "/dashboard"}]]}]])

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