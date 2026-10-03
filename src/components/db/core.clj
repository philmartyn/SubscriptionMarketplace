(ns components.db.core
  (:require [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))


(defmethod ig/init-key :database.sql/connection [_ db-spec]
  (def db-spec db-spec)
  (jdbc/with-options
   (jdbc/get-datasource db-spec)
   {:builder-fn rs/as-unqualified-lower-maps}))