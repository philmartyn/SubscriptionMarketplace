(ns submarket.dashboard.env
  (:require
    [clojure.tools.logging :as log]
    [submarket.dashboard.dev-middleware :refer [wrap-dev]]))

(def defaults
  {:init       (fn []
                 (log/info "\n-=[dashboard starting using the development or test profile]=-"))
   :start      (fn []
                 (log/info "\n-=[dashboard started successfully using the development or test profile]=-"))
   :stop       (fn []
                 (log/info "\n-=[dashboard has shut down successfully]=-"))
   :middleware wrap-dev
   :opts       {:profile       :dev}})
