(ns shared.middleware.core
  "The middleware every request passes through.

  Deliberately free of any dev/-only namespace. This file is loaded by
  app.handler, which runs in production, where dev/ is not on the classpath -
  requiring submarket.dashboard.env from here is what made the app unable to
  boot outside development."
  (:require
   [modules.auth.session :as auth.session]
   [ring.middleware.defaults :as defaults]))

(defn wrap-base
  "Ring's site defaults, with the session backed by Postgres.

  The store is built once, here, so it holds the datasource the system already
  opened rather than opening one per request. Sessions are server side because
  they have to be revocable: signing out must invalidate the session even
  though the browser still holds the cookie.

  The components the auth handlers need - the database, the mailer and the
  base url for verification links - ride on (:system request), attached inside
  the defaults chain so the session and params are already parsed by the time
  a handler reads them."
  [{:keys [site-defaults-config database mailer base-url]}]
  (let [store (auth.session/session-store database)
        system {:db     database
                :mailer mailer
                :config {:base-url base-url}}]
    (fn [handler]
      (defaults/wrap-defaults
       (fn [request]
         (handler (assoc request :system system)))
       (assoc-in site-defaults-config [:session :store] store)))))