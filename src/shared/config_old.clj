(ns shared.config_old
  (:require
   [integrant.core :as ig]
   [aero.core :as aero]))

(defmethod aero/reader 'ig/ref
  [{:keys [profile] :as opts} tag value]
  (ig/ref value))

(defn load-config [environment]
  {:pre [(keyword? environment)]}
  (aero/read-config "src/system/system.edn" {:profile environment}))

(def load-config-memo (memoize load-config))

(comment

(load-config :dev)
)

