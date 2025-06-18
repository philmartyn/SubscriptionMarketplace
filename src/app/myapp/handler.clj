(ns app.myapp.handler
  (:require [reitit.ring :as ring]
            [modules.users.routes :as users-routes]
            [modules.subscriptions.routes :as subs-routes]))

(def app
  (ring/ring-handler
   (ring/router
    [(users-routes/routes)
     (subs-routes/routes)])))