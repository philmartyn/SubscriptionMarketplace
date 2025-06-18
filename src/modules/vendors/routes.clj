(ns modules.vendors.routes)

(defn routes []
  ["/vendors" {:get (fn [_] {:status 200 :body "List vendors"})}])