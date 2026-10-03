(ns app.core
  (:require [app.handler]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [kit.edge.server.undertow]
            [modules.routes]
            [shared.config :as config]
            [submarket.dashboard.env :refer [defaults]]
            [views.routes]))

;; log uncaught exceptions in threads
(Thread/setDefaultUncaughtExceptionHandler
 (fn [thread ex]
   (log/error {:what :uncaught-exception
               :exception ex
               :where (str "Uncaught exception on" (.getName thread))})))

(defonce system (atom nil))

(defn stop-app []
  ((or (:stop defaults) (fn [])))
  (some-> (deref system) (ig/halt!)))

(defn start-app [& [params]]
  ((or (:start params) (:start defaults) (fn [])))
  (->> (config/system-config (or (:opts params) (:opts defaults) {}))
       (ig/expand)
       (ig/init)
       (reset! system)))

(defn -main [environment]
  (start-app)
  (.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (stop-app) (shutdown-agents)))))
