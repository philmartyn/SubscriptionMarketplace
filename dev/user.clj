(ns user
  (:require
   [next.jdbc.result-set :as rs]
   [ragtime.next-jdbc :as next-jdbc]
   [ragtime.jdbc :as jdbc]
   [components.db.core]
   [integrant.core :as ig]
   [ragtime.repl]
   [aero.core :as aero]
   [app.handler]
   [app.core]
   [next.jdbc :as next.jdbc]
   [integrant.repl :as ig-repl :refer [clear go halt prep init reset reset-all]]
   [clj-http.client :as client]
   [clojure.tools.namespace.repl :as tools]))


;(ig-repl/set-prep! #(ig/expand (config/load-config :dev) (ig/deprofile [:dev])))
;(tools/set-refresh-dirs "dev,src")
;(go)
;(defn reset-app [] (reset))

(def db-spec {:port 5432
              :user "admin"
              :dbtype "postgres"
              :dbname "sub_market_test"
              :password "1234"})

(defmethod ig/init-key :database.sql/migration-config [_ db-spec]
  (def db-spec db-spec)
  {:datastore  (next-jdbc/sql-database db-spec #_{:connection-uri "postgresql://admin:1234@localhost:5432/sub_market_test"})
   :migrations (next-jdbc/load-resources "migrations")})


(def config
  {:datastore  (next-jdbc/sql-database db-spec #_{:connection-uri "postgresql://admin:1234@localhost:5432/sub_market_test"})
   :migrations (next-jdbc/load-resources "migrations")})

(comment
(ragtime.repl/migrate config)
(ragtime.repl/rollback config 7)

(reset)

)




;psql postgresql://postgres:password@localhost:5432/postgres
;
;
; psql -U admin -d sub_market_test -h localhost
;
;
; GRANT ALL PRIVILEGES ON DATABASE sub_market_test TO admin;
;
; postgresql://admin:1234@localhost:5432/sub_market_test?sslmode=require