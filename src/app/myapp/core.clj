(ns app.myapp.core
  (:require [integrant.core :as ig]))

(defn -main []
  (ig/init (read-string (slurp "src/system/config.edn"))))