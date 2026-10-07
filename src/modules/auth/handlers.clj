(ns modules.auth.handlers
  "HTTP handlers for the auth forms.

  Thin by design: pick the submitted values out of the request, hand them to
  modules.auth.service, and turn the result map into a redirect or a re-render.
  The service layer is where the rules live; nothing here knows how to hash a
  password or check an email.

  Every handler answers both shapes a form submission can take. An htmx request
  (HX-Request header) gets a small response it can swap in, or an HX-Redirect
  header to navigate; a plain form post gets a full page re-render or a 303.

  The components the handlers need - the database, the mailer and the base url
  for verification links - travel on (:system request), attached by
  shared.middleware.core/wrap-base from the system configuration."
  (:require
   [modules.auth.service :as service]
   [ring.util.http-response :as http-response]
   [views.auth :as views]))

(defn- services [request]
  (:system request))

(defn- db [request]
  (:db (services request)))

(defn- mailer [request]
  (:mailer (services request)))

(defn- config [request]
  (:config (services request)))

(defn- htmx? [request]
  (= "true" (get-in request [:headers "hx-request"])))

(defn- submitted
  "The form values, unpicked. Values are echoed back into the form on error,
  passwords and not-yet-received fields excepted."
  [request]
  (select-keys (:params request) [:email :password :name]))

(defn- authenticated-response
  "The reply once authentication succeeded: sign the account into the session
  and send the browser to its dashboard. :session must be on the response in
  both branches, because that is what makes Ring persist the session and set
  the cookie - for htmx the redirect then carries that cookie along."
  [request {:keys [account-id account-type]}]
  (let [target  (if (= :vendor account-type) "/vendor/dashboard" "/dashboard")
        session (assoc (:session request) :account-id account-id)
        base    (-> (if (htmx? request)
                      (http-response/ok "")
                      (http-response/see-other target))
                    (assoc :session session))]
    (if (htmx? request)
      (assoc-in base [:headers "HX-Redirect"] target)
      base)))

(defn- re-render
  "The failure shape: the form again with its errors, values kept. A whole
  document for a plain post, just the form for htmx."
  [request mode errors]
  (let [opts {:mode mode :errors errors :values (submitted request)}]
    (if (htmx? request)
      (views/fragment opts)
      (views/page opts))))

(defn- redirect-to
  "Navigate somewhere after a GET-style action: htmx via header, plain via 302."
  [request location]
  (if (htmx? request)
    (-> (http-response/ok "")
        (assoc-in [:headers "HX-Redirect"] location))
    (http-response/found location)))

(defn signin
  "POST /auth/signin. Branches on the account's type after verifying."
  [request]
  (let [result  (service/authenticate (db request) (submitted request))
        account (:account result)]
    (if (:ok? result)
      (authenticated-response request
                              {:account-id   (:id account)
                               :account-type (service/account-type-of account)})
      (re-render request :signin (:errors result)))))

(defn- register!
  [request account-type]
  (let [result (service/register! (db request) (mailer request) (config request)
                                  (assoc (submitted request) :account-type account-type))]
    (if (:ok? result)
      (authenticated-response request
                              (select-keys result [:account-id :account-type]))
      (re-render request (if (= :vendor account-type) :vendor-signup :signup)
                 (:errors result)))))

(defn signup
  "POST /auth/signup - a subscriber account."
  [request]
  (register! request :user))

(defn vendor-signup
  "POST /auth/vendor-signup - a vendor account."
  [request]
  (register! request :vendor))

(defn verify-email
  "GET /auth/verify?token=... (the emailed link) or a POST of the same.

  Consumes the single-use token and sends the browser to /dashboard. An
  invalid, expired or replayed link quietly points back at sign-in."
  [request]
  (let [ok? (:ok? (service/verify-email (db request)
                                        (get-in request [:params :token])))]
    (redirect-to request (if ok? "/dashboard" "/auth/signin?verify=invalid"))))

(defn resend-verification
  "POST /auth/resend-verification, from the dashboard banner of an account
  that has not verified yet."
  [request]
  (let [result (service/resend-verification
                (db request) (mailer request) (config request)
                (get-in request [:session :account-id]))]
    (redirect-to request (if (:ok? result) "/dashboard" "/auth/signin"))))

(defn signout
  "POST /auth/signout.

  Setting :session to nil tells Ring to revoke the session server side; the
  :session-cookie-attrs are what actually delete the cookie in the browser,
  which Ring does not do for us (see the progress notes)."
  [request]
  (-> (if (htmx? request)
        (-> (http-response/ok "")
            (assoc-in [:headers "HX-Redirect"] "/"))
        (http-response/see-other "/"))
      (assoc :session nil :session-cookie-attrs {:max-age 0})))