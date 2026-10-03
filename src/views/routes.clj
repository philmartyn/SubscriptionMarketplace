(ns views.routes
  (:require
    [clojure.tools.logging :as log]
    [shared.middleware.exception :as exception]
    [shared.middleware.formats :as formats]
    ;[views.htmx :refer [page pagelet] :as htmx]
    [views.dashboard.home :as home]
    [views.dashboard.auth :as auth]
    [integrant.core :as ig]
    [reitit.ring.middleware.muuntaja :as muuntaja]
    [reitit.ring.middleware.parameters :as parameters]))


;; Routes
(defn ui-routes [_opts]
  [["/" {:get (fn [r] (log/info "!!!!!!!") [:div "Test"] (home/home r))}]
   ["/auth/login" {:get auth/login}
   ;"/auth/signin" {:get (fn [request] (prn 'sign 1))
   ;:post (fn [request] (prn 'sign 2))}
   ]
   ])

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
