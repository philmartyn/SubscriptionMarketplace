(ns components.db.migrations
  "Ragtime migration support.

  Migrations live as .edn resources under resources/migrations and are tracked
  in a ragtime_migrations table, so each one runs exactly once per database.

  ragtime separates the two objects involved: the *store* is a migratable
  wrapping a datasource, and the *index* maps migration ids to migrations. Most
  of the noise below is just assembling those three values consistently."
  (:require
   [clojure.tools.logging :as log]
   [ragtime.core :as ragtime]
   [ragtime.next-jdbc :as ragtime-jdbc]
   [ragtime.protocols :as p]))

(def ^:private reporter
  "Ragtime calls this as (reporter store operation migration-id)."
  (fn [_store operation migration-id]
    (log/info "migration" :direction operation :id migration-id)))

(defn- store
  [datasource]
  (ragtime-jdbc/sql-database datasource))

(defn- all-migrations
  []
  (vec (ragtime-jdbc/load-resources "migrations")))

(defn- index
  []
  (ragtime/into-index (all-migrations)))

(defn migrate!
  "Apply every migration that has not run yet.

  Returns the migrations that were applied, so an empty result means the
  database was already up to date."
  [datasource]
  (let [before (into #{} (map p/id) (ragtime/applied-migrations (store datasource) (index)))
        _ (ragtime/migrate-all (store datasource) (index) (all-migrations)
                               {:reporter reporter})
        after (into #{} (map p/id) (ragtime/applied-migrations (store datasource) (index)))]
    (log/info "Database schema up to date" :applied (- (count after) (count before)))
    after))

(defn rollback!
  "Undo the n most recently applied migrations.

  Pass a negative n to roll forward again."
  [datasource n]
  (log/info "Rolling back" :count n)
  (ragtime/rollback-last (store datasource) (index) n {:reporter reporter}))

(defn status
  "Applied and pending migration ids, for the CLI."
  [datasource]
  (let [s (store datasource)
        migrations (all-migrations)
        applied (into #{} (map p/id) (ragtime/applied-migrations s (ragtime/into-index migrations)))]
    {:applied (sort applied)
     :pending (sort (remove applied (map p/id migrations)))}))
