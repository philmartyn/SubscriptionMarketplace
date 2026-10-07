(ns app.core
  (:require
   [app.handler]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [kit.edge.server.undertow]
   [modules.routes]
   [shared.config :as config]
   [views.routes]))

;; log uncaught exceptions in threads
(Thread/setDefaultUncaughtExceptionHandler
 (fn [thread ex]
   (log/error {:what "Uncaught exception in thread"
               :exception ex
               :where (.getName thread)})))

(defonce system (atom nil))

(def ^:private default-opts
  "What a bare (start-app) assumes: local development.

  This used to come from submarket.dashboard.env, a namespace under dev/. dev/
  is not on the classpath in production, so requiring it here meant app.core
  could not load at all outside development or test - the application was
  unbootable and nothing failed until someone tried to run it."
  {:profile :dev})

(defn stop-app []
  (some-> (deref system) (ig/halt!))
  (log/info "\n-=[ dashboard has shut down successfully ]=-"))

(defn start-app
  "Boot the system under a profile.

  Accepts {:opts {:profile k}}, which is how test selects :test. A :start hook
  is honoured for compatibility with the kit scaffolding it replaced."
  [& [params]]
  (let [opts (or (:opts params) default-opts)]
    (when-let [start (:start params)]
      (start))
    (log/info "\n-=[ dashboard starting using the" (name (:profile opts))
              "profile ]=-")
    (->> (config/system-config opts)
         (ig/expand)
         (ig/init)
         (reset! system))))

(defn -main [_environment]
  (start-app)
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. (fn [] (stop-app) (shutdown-agents)))))
