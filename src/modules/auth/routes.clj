(ns modules.auth.routes
  (:require [modules.auth.handlers :as auth]))

(defn routes []
  ["/auth/signup" {:get auth/signup}
   "/auth/signin" {:get auth/signup}])