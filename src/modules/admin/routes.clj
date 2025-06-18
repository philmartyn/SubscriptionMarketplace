(ns modules.admin.routes)

(defn routes []
  ["/admin" {:get (fn [_] {:status 200 :body "List admin"})}])