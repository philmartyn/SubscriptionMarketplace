(ns modules.users.routes)

(defn routes []
  ["/users" {:get (fn [_] {:status 200 :body "List users"})}])