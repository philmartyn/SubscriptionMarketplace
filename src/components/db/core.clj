(ns components.db.core
  (:require
   [clojure.tools.logging :as log]
   [components.db.migrations :as migrations]
   [integrant.core :as ig]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

(defn datastore
  "Build a datasource from a database spec.

  The builder-fn is not optional. Without it next.jdbc namespaces every result
  map after its table, so a query on accounts comes back as
  #:accounts{:id 1} and (:id row) is nil. That failure is silent: rows are
  found, they are just keyed wrongly. Everything that talks to Postgres goes
  through here so the CLI, the server and the tests cannot disagree."
  [db-spec]
  (jdbc/with-options
    (jdbc/get-datasource db-spec)
    {:builder-fn rs/as-unqualified-lower-maps}))

(defmethod ig/init-key :database.sql/connection
  [_ {:keys [migrate?] :as db-spec}]
  (let [datastore (datastore db-spec)]
    ;; Opt in per environment via :migrate? true in system.edn. Migrating on
    ;; every boot is convenient locally but dangerous in production, where a
    ;; schema change belongs to a deliberate deploy step rather than to whatever
    ;; revision an instance happened to start on.
    (if migrate?
      (migrations/migrate! datastore)
      (log/debug "Migrations not run for this datasource"))
    datastore))
