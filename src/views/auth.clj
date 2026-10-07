(ns views.auth
  "Sign in and sign up pages.

  Email and password are the only methods offered; there is deliberately no
  provider plumbing here. Each form carries action and hx-post pointing at the
  same endpoint, so htmx swaps the reply in place and a plain POST renders the
  same result without JavaScript.

  Three modes share one card: signin, signup (a subscriber), and vendor-signup.
  Subscribers and vendors are separate entities, signed up on separate pages
  with separate endpoints.

  Forms carry a hidden anti-forgery token whenever one is bound. Ring binds the
  var during a real request; unit tests that call these functions directly run
  with no token and see no hidden input, which is the honest shape for a page
  rendered outside a request."
  (:require
   [views.htmx :as htmx]
   [views.layout :as layout]))

(def modes
  "The three shapes a form takes: the endpoint it posts to, the fields it
  collects, and the copy around it."
  {:signin        {:heading     "Welcome back"
                   :subheading  "Sign in to manage your plans and deliveries."
                   :title       (str "Sign in - " layout/site-name)
                   :description "Sign in to manage your subscriptions and saved plans."
                   :action      "/auth/signin"
                   :submit      "Sign in"
                   :fields      [["email" "Email" "email" "email"]
                                 ["password" "Password" "password" "current-password"]]}
   :signup        {:heading     (str "Create your " layout/site-name " account")
                   :subheading  "Subscribe to a plan and track your subscriptions."
                   :title       (str "Create an account - " layout/site-name)
                   :description (str "Create a " layout/site-name " account to subscribe to a plan.")
                   :action      "/auth/signup"
                   :submit      "Create account"
                   :fields      [["name" "Name" "text" "name"]
                                 ["email" "Email" "email" "email"]
                                 ["password" "Password" "password" "new-password"]]}
   :vendor-signup {:heading     (str "Create your vendor account")
                   :subheading  "List a plan and manage your customer deliveries."
                   :title       (str "Vendor signup - " layout/site-name)
                   :description (str "Create a " layout/site-name " vendor account to list your plans.")
                   :action      "/auth/vendor-signup"
                   :submit      "Create vendor account"
                   :fields      [["name" "Name" "text" "name"]
                                 ["email" "Email" "email" "email"]
                                 ["password" "Password" "password" "new-password"]]}})

(defn- mode-config [mode]
  (get modes mode (get modes :signin)))

(defn- input-attrs
  "Attributes for one credential input. A submitted value is echoed back only
  for fields that are safe to echo - never a password."
  [[id _label type autocomplete] {:keys [values]}]
  (cond-> {:type          type
           :id            id
           :name          id
           :required      true
           :autocomplete  autocomplete
           :class         "input w-full"
           :placeholder   (case id
                            "email" "you@example.com"
                            "password" "Your password"
                            "name" "Your name"
                            "")}
    (and (not= "password" id)
         (some? (get values (keyword id))))
    (assoc :value (get values (keyword id)))))

(defn- cross-links
  "Where to go instead, under the form."
  [mode]
  [:div {:class "mt-6 space-y-1 text-center text-sm text-base-content/70"}
   (if (= mode :signin)
     [:p [:span "New to " layout/site-name "? "]
      [:a {:href "/auth/signup" :class "link link-primary"} "Create an account"]]
     [:p [:span "Already have an account? "]
      [:a {:href "/auth/signin" :class "link link-primary"} "Sign in"]])
   (if (= mode :vendor-signup)
     [:p [:span "Want to subscribe instead? "]
      [:a {:href "/auth/signup" :class "link"} "Create a customer account"]]
     [:p [:span "Selling something? "]
      [:a {:href "/auth/vendor-signup" :class "link"} "Create a vendor account"]])])

(defn auth-form
  "The credential form alone, so that htmx can swap this element in place of
  the previous form without re-rendering the page around it."
  [{:keys [mode errors] :as opts}]
  (let [{:keys [action submit fields]} (mode-config mode)
        form-error (:credentials errors)]
    [:form {:action action :method "post" :hx-post action :hx-swap "outerHTML"}
     (layout/csrf-field)
     (when form-error
       [:div {:class "alert alert-error mb-4"} [:span form-error]])
     (for [field fields]
       (let [[id label] field]
         ^{:key id}
         [:label {:class "flex w-full flex-col gap-1"}
          [:span {:class "text-sm font-medium"} label]
          [:input (input-attrs field opts)]
          (when-let [error (get errors (keyword id))]
            [:span {:class "mt-1 text-sm text-error"} error])]))
     [:button {:type "submit" :class "btn btn-primary btn-block mt-6"} submit]
     (cross-links mode)]))

(defn auth-card
  "Shared card shell for a page: heading, copy, notice, and the form."
  [{:keys [mode notice] :as opts}]
  (let [{:keys [heading subheading]} (mode-config mode)]
    [:div {:class "flex min-h-[calc(100vh-8rem)] items-center justify-center py-12"}
     [:div {:class "card w-full max-w-md border border-base-300 shadow-lg"}
      [:div {:class "card-body"}
       [:h1 {:class "card-title text-2xl"} heading]
       [:p {:class "mb-6 text-sm text-base-content/70"} subheading]
       (when notice
         [:div {:class "alert alert-info mb-4"} [:span notice]])
       (auth-form opts)]]]))

(defn page
  "A complete document for one of the auth pages: chrome, card, form."
  [{:keys [mode errors values notice]}]
  (let [{:keys [title description]} (mode-config mode)]
    (layout/page
     {:title title :description description}
     (auth-card {:mode mode :errors errors :values values :notice notice}))))

(defn fragment
  "Just the form, the response htmx swaps in after a failed submit."
  [{:keys [mode errors values]}]
  (htmx/pagelet {} (auth-form {:mode mode :errors errors :values values})))

(defn signin
  "GET /auth/signin."
  [request]
  (page {:mode :signin
         :notice (when (= "invalid" (get-in request [:params :verify]))
                   "That verification link is invalid or has already been used.")}))

(defn signup
  "GET /auth/signup - a subscriber account."
  [_request]
  (page {:mode :signup}))

(defn vendor-signup
  "GET /auth/vendor-signup - a vendor account."
  [_request]
  (page {:mode :vendor-signup}))