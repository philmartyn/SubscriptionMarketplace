(ns modules.auth.middleware
  "Route guards for authenticated surfaces.

  The session store already rejects revoked and expired sessions when it reads
  them, so a session that is present on the request is, by construction, still
  valid. These guards only decide what to do with the two shapes a session can
  be in: anonymous, holding at most an anti-forgery token, and signed in, with
  an account-id and account-type."
  (:require
   [ring.util.http-response :as http-response]))

(defn- htmx? [request]
  (= "true" (get-in request [:headers "hx-request"])))

(defn- destination-response
  "Send the browser somewhere.

  htmx needs the HX-Redirect header to navigate away from the page it is
  holding on to; a plain request gets a redirect it can follow on its own."
  [request location]
  (if (htmx? request)
    (-> (http-response/ok "")
        (assoc-in [:headers "HX-Redirect"] location))
    (http-response/found location)))

(defn wrap-auth-required
  "Reject requests without a signed-in session.

  Use on any page that only exists behind authentication."
  [handler]
  (fn [request]
    (if (:account-id (:session request))
      (handler request)
      (destination-response request "/auth/signin"))))

(defn wrap-account-type
  "Reject signed-in requests whose account type is not the required one.

  Reitit calls this as (wrap-account-type handler opts): the options come from
  the route's middleware vector, written [wrap-account-type {:account-type
  :vendor, :redirect \"/dashboard\"}]. It expects to sit behind
  wrap-auth-required, so only genuine sessions are judged."
  [handler {:keys [account-type redirect]
            :or   {redirect "/"}}]
  (fn [request]
    (if (= account-type (:account-type (:session request)))
      (handler request)
      (destination-response request redirect))))