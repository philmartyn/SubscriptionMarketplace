(ns views.auth
  "Sign in and sign up pages.

  These are UI only. The forms carry hx-post attributes pointing at the API as
  the intended wiring, but /api/auth/signin is still a GET-only stub returning
  nil, so submitting them does not do anything yet."
  (:require
   [views.layout :as layout]))

(def form-script
  "Show the panel matching the selected provider.

  The readyState check matters because this page is reached through hx-boost:
  htmx inserts the new body and then runs its scripts, so readyState is already
  'complete' and DOMContentLoaded will never fire again."
  "window.addEventListener('DOMContentLoaded', bind);
 function bind() {
  var group = document.getElementById('provider-tabs');
  if (!group || group.dataset.bound === 'true') { return; }
  group.dataset.bound = 'true';
  group.addEventListener('change', function (e) {
   var input = e.target;
   if (input.name !== 'provider') { return; }
   var panels = document.querySelectorAll('[data-provider-panel]');
   for (var i = 0; i < panels.length; i++) {
    panels[i].hidden = panels[i].getAttribute('data-provider-panel') !== input.value;
   }
  });
 }
 if (document.readyState !== 'loading') { bind(); }")

(defn provider-tabs
  "daisyUI radio tabs. The labels are real radio inputs so the form still works
  as a plain submit without JavaScript."
  [selected]
  [:div {:id "provider-tabs" :class "tabs tabs-box mb-4"}
   [:label {:class (str "tab " (when (= "email" selected) "tab-active"))}
    [:input {:type "radio" :name "provider" :value "email" :checked (= "email" selected)}]
    "Email"]
   [:label {:class (str "tab " (when (= "google" selected) "tab-active"))}
    [:input {:type "radio" :name "provider" :value "google" :checked (= "google" selected)}]
    "Google"]
   [:label {:class (str "tab " (when (= "apple" selected) "tab-active"))}
    [:input {:type "radio" :name "provider" :value "apple" :checked (= "apple" selected)}]
    "Apple"]])

(defn provider-button
  [{:keys [provider]}]
  [:button {:type "button"
            :class "btn btn-outline btn-block"}
   (case provider
     "google" "Continue with Google"
     "apple" "Continue with Apple"
     "Continue with email")])

(defn email-panel
  [provider-id fields submit-label]
  [:div {:data-provider-panel provider-id :hidden (not= provider-id "email")}
   (for [[id label type] fields]
     ^{:key id}
     [:label {:class "flex w-full flex-col gap-1"}
      [:span {:class "text-sm font-medium"} label]
      [:input {:type type
               :name id
               :id (str provider-id "-" id)
               :required true
               :autocomplete (if (= "password" id) "current-password" "email")
               :class "input w-full"
               :placeholder (case id
                              "email" "you@example.com"
                              "password" "Your password"
                              "name" "Your name"
                              "")}]])
   [:button {:type "submit" :class "btn btn-primary btn-block mt-6"} submit-label]])

(defn auth-card
  "Shared card shell for both pages."
  [{:keys [heading subheading selected mode]}]
  [:div {:class "flex min-h-[calc(100vh-8rem)] items-center justify-center py-12"}
   [:div {:class "card w-full max-w-md border border-base-300 shadow-lg"}
    [:div {:class "card-body"}
     [:h1 {:class "card-title text-2xl"} heading]
     [:p {:class "mb-6 text-sm text-base-content/70"} subheading]
     [:form {:action (if (= "signup" mode) "/auth/signup" "/auth/signin")
             :method "post"
             :hx-post (if (= "signup" mode) "/api/auth/signup" "/api/auth/signin")
             :hx-swap "outerHTML"}
      (provider-tabs selected)
      (email-panel
       "email"
       (if (= "signup" mode)
         [["name" "Name" "text"] ["email" "Email" "email"] ["password" "Password" "password"]]
         [["email" "Email" "email"] ["password" "Password" "password"]])
       (if (= "signup" mode) "Create account" "Sign in"))
      [:div {:class "divider my-4 text-xs uppercase opacity-60"} "or"]
      [:div {:class "space-y-2"}
       (provider-button {:provider "google"})
       (provider-button {:provider "apple"})]
      [:p {:class "mt-6 text-center text-sm text-base-content/70"}
       (if (= "signup" mode)
         [:span "Already have an account? " [:a {:href "/auth/signin" :class "link link-primary"} "Sign in"]]
         [:span "New to " layout/site-name "? " [:a {:href "/auth/signup" :class "link link-primary"} "Create an account"]])]]]]])

(defn signin
  "GET /auth/signin."
  [_request]
  (layout/page
   {:title (str "Sign in - " layout/site-name)
    :description "Sign in to manage your subscriptions and saved plans."}
   [:script {:type "text/javascript"} form-script]
   (auth-card {:heading "Welcome back"
               :subheading "Sign in to manage your plans and deliveries."
               :selected "email"
               :mode "signin"})))

(defn signup
  "GET /auth/signup."
  [_request]
  (layout/page
   {:title (str "Create an account - " layout/site-name)
    :description (str "Create a " layout/site-name
                      " account to subscribe to a plan or to list your own.")}
   [:script {:type "text/javascript"} form-script]
   (auth-card {:heading (str "Create your " layout/site-name " account")
               :subheading "Subscribe to a plan, or start selling your own products."
               :selected "email"
               :mode "signup"})))