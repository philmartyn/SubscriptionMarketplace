(ns modules.marketplace.routes)

(defn routes []
  ["/marketplaces" {:get (fn [_] {:status 200 :body "List marketplaces"})}])