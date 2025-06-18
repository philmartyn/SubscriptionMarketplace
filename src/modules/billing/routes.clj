(ns modules.billing.routes)

(defn routes []
  ["/billing" {:get (fn [_] {:status 200 :body "List billings"})}])