(ns modules.subscriptions.routes)

(defn routes []
  ["/subscriptions" {:get (fn [_] {:status 200 :body "List subscriptions"})}])