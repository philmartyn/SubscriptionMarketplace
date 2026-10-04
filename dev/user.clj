(ns user
  "REPL helpers for development.

  Migrations are no longer wired up from here. They run either through
  `clojure -M:db migrate` or automatically at boot via the :migrate? flag in
  system.edn, which is enabled for :dev and :test only."
  (:require
   [app.core]
   [app.handler]
   [clojure.tools.namespace.repl :as tools]
   [components.db.core]
   [integrant.repl :as ig-repl :refer [clear go halt init prep reset reset-all]]
   [shared.config :as config]))

(defn reset-app
  "Stop the system and start it again, picking up changed namespaces."
  []
  (reset))

;; Useful at the REPL:
;;
;;   (start-app)          boot on port 3000
;;   (start-app :opts {:env :dev :port 3100})   boot on another port
;;   (stop-app)           shut down
;;
;; To explore the database:
;;
;;   (psql postgresql://admin:1234@localhost:5432/sub_market_test)
