(ns views.dashboard.home
  "The subscriber dashboard - the first surface behind authentication.

  What the auth flow needs from this page: it renders while email verification
  is still pending, it offers the form that resends the verification link, and
  it signs the session out. The marketplace itself (plans, subscriptions,
  payments) is not built yet, so the page says so rather than pretending."
  (:require
   [views.layout :as layout]))

(defn home
  "GET /dashboard. The route guard has already confirmed a signed-in session."
  [request]
  (let [session     (:session request)
        verified?   (:verified? session)
        vendor?     (= :vendor (:account-type session))
        ;; The stand-in for the emailed link, which only exists in dev/test.
        dev-verify? (get-in request [:system :config :dev-verify?])]
    (layout/page
     {:title       (str "Dashboard - " layout/site-name)
      :description "Manage your plans and deliveries."}
     [:div {:class "mx-auto w-full max-w-3xl px-4 py-10"}
      [:h1 {:class "text-2xl font-bold"} "Your dashboard"]
      (when-not verified?
        [:div {:class "alert alert-info mt-4"}
         [:span (str "Your email is not verified yet. Verification is required "
                     "before orders can be placed; the link in your inbox is "
                     "valid for 24 hours.")]
         [:div {:class "mt-2 flex flex-wrap items-center gap-2"}
          [:form {:action "/auth/resend-verification" :method "post"
                  :hx-post "/auth/resend-verification" :hx-swap "outerHTML"}
           (layout/csrf-field)
           [:button {:type "submit" :class "btn btn-outline btn-sm"}
            "Resend verification email"]]
          (when dev-verify?
            [:form {:action "/auth/dev-verify" :method "post"
                    :hx-post "/auth/dev-verify" :hx-swap "outerHTML"}
             (layout/csrf-field)
             [:button {:type "submit" :class "btn btn-ghost btn-sm"}
              "Simulate email click (dev)"]])]])
      (when vendor?
        [:div {:class "alert alert-info mt-4"}
         [:span "This is the subscriber account. "]
         [:a {:href "/vendor/dashboard" :class "link link-primary"}
          "Your vendor dashboard"]])
      [:div {:class "mt-8 text-sm text-base-content/70"}
       "Plans, deliveries and billing will live here."]
      [:form {:class "mt-8"
              :action "/auth/signout" :method "post"
              :hx-post "/auth/signout" :hx-swap "outerHTML"}
       (layout/csrf-field)
       [:button {:type "submit" :class "btn btn-outline btn-sm"} "Sign out"]]])))