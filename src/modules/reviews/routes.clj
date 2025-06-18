(ns modules.reviews.routes)

(defn routes []
  ["/reviews" {:get (fn [_] {:status 200 :body "List reviews"})}])