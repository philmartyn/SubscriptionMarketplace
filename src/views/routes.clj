(ns views.routes
  (:require
   [integrant.core :as ig]
   [reitit.ring.middleware.muuntaja :as muuntaja]
    ;[views.htmx :refer [page pagelet] :as htmx]
   [reitit.ring.middleware.parameters :as parameters]
   [shared.middleware.exception :as exception]
   [shared.middleware.formats :as formats]
   [views.auth :as auth]
   [views.dashboard.home :as home]
   [views.landing.index :as landing]))

;; Routes
(defn ui-routes [_opts]
  [["/" {:get landing/landing}]
   ["/dashboard" {:get home/home}]
   ["/auth/signin" {:get auth/signin}]
   ["/auth/signup" {:get auth/signup}]])

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
