(ns components.db.cli
  "Command line entry point for schema changes.

  Usage:
    clojure -M:db migrate          apply pending migrations
    clojure -M:db rollback [n]     undo the n most recent migrations (default 1)
    clojure -M:db status           list applied and pending migrations"
  (:require
   [clojure.tools.logging :as log]
   [components.db.core :as db]
   [components.db.migrations :as migrations]
   [shared.config :as config])
  (:gen-class))

(defn- datastore
  "A datasource built from the configured database spec.

  Deliberately not the integrant component, so the CLI works without booting
  the web server, but built by the same function so the two cannot drift."
  []
  (db/datastore (:database.sql/connection (config/system-config {}))))

(defn- status!
  [ds]
  (let [{:keys [applied pending]} (migrations/status ds)]
    (doseq [id applied]
      (println (format "  applied  %s" id)))
    (doseq [id pending]
      (println (format "  pending  %s" id)))
    (println (format "%d applied, %d pending" (count applied) (count pending)))))

(defn -main
  [& [command & args]]
  (let [ds (datastore)]
    (case (or command "status")
      "migrate" (migrations/migrate! ds)
      "rollback" (migrations/rollback! ds (if (seq args) (Long/parseLong (first args)) 1))
      "status" (status! ds)
      (do (println "Unknown command:" command)
          (println "Expected one of: migrate, rollback [n], status")
          (System/exit 1)))
    (shutdown-agents)
    (log/info "Done")))
