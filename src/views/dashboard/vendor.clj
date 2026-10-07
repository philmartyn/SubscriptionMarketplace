(ns views.dashboard.vendor
  "The vendor dashboard - where a signed-in vendor lands.

  A deliberate stub: vendors have no products, orders or payouts yet, so this
  page describes what is coming instead of inventing data. What it does wire
  up is the auth contract - the verification banner and sign out."
  (:require
   [views.layout :as layout]))

(defn home
  "GET /vendor/dashboard. Guards on the route have already confirmed the
  session is signed in and belongs to a vendor."
  [request]
  (let [verified? (:verified? (:session request))]
    (layout/page
     {:title       (str "Vendor dashboard - " layout/site-name)
      :description "Manage your plans, orders and payouts."}
     [:div {:class "mx-auto w-full max-w-3xl px-4 py-10"}
      [:h1 {:class "text-2xl font-bold"} "Vendor dashboard"]
      (when-not verified?
        [:div {:class "alert alert-info mt-4"}
         [:span "Your email is not verified yet. You can list plans once it is."]
         [:form {:class "mt-2"
                 :action "/auth/resend-verification" :method "post"
                 :hx-post "/auth/resend-verification" :hx-swap "outerHTML"}
          (layout/csrf-field)
          [:button {:type "submit" :class "btn btn-outline btn-sm"}
           "Resend verification email"]]])
      [:div {:class "mt-8 text-sm text-base-content/70"}
       "Plans, orders and payouts will live here."]
      [:form {:class "mt-8"
              :action "/auth/signout" :method "post"
              :hx-post "/auth/signout" :hx-swap "outerHTML"}
       (layout/csrf-field)
       [:button {:type "submit" :class "btn btn-outline btn-sm"} "Sign out"]]])))