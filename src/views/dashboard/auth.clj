(ns views.dashboard.auth
  (:require [views.htmx :refer [page pagelet] :as htmx]))

(defn sign-up [request])

(defn login [request]
  (page {:lang "en"}
        [:head
         [:meta {:charset "UTF-8"}]
         [:title "Htmx + Kit"]
         [:link {:href "/output.css" :rel "stylesheet"}]
         [:script {:src "https://unpkg.com/htmx.org@2.0.4/dist/htmx.min.js" :defer true}]]
        [:body
         [:h1 "Welcome to Htmx + Kit module"]

         [:form {:style {:display :flex}}
          [:input.input {:id "email" :name "email" :type "Email" :placeholder "Enter Email here"}]
          [:input.input {:id "password" :name "password" :type "Password" :placeholder "Enter Password here"}]
          [:button.btn {:hx-post "/api/auth/signin" :hx-swap "outerHTML"} "Submit"]]]))