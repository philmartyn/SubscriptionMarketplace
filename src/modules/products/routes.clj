(ns modules.products.routes)

(defn routes []
  ["/products" {:get (fn [_] {:status 200 :body "List products"})}])