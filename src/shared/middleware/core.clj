(ns shared.middleware.core
  (:require
   [ring.middleware.defaults :as defaults]
   [ring.middleware.session.cookie :as cookie]
    ;[submarket.dashboard.web.middleware.supertokens-sessions :as supertokens]
   [submarket.dashboard.env :as env]))

(defn wrap-supertokens-session [handler]
  (fn [request]
    #_(if-let [session-info (supertokens/verify-session request)] ; hypothetical SuperTokens verification
        (handler (assoc request :session session-info)) ; Attach session info to request
        {:status 302 :headers {"Location" "/auth/login"} :body ""}))) ; Return 401 error

(defn wrap-base
  [{:keys [metrics site-defaults-config cookie-secret] :as opts}]
  (let [cookie-store (cookie/cookie-store {:key (.getBytes ^String cookie-secret)})]
    (fn [handler]
      (cond-> ((:middleware env/defaults) handler opts)
        true (defaults/wrap-defaults
              (assoc-in site-defaults-config [:session :store] cookie-store))
        false wrap-supertokens-session))))
