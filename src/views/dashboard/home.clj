(ns views.dashboard.home
  (:require [views.htmx :refer [page]]
            #_[submarket.dashboard.web.middleware.supertokens-sessions :as st]
            #_[views.dashboard.auth :as auth]))

(defn home [request]
  (def request request)
  (page
   {:lang "en"}
   [:head
    [:meta {:charset "UTF-8"}]
    [:title "Htmx + Kit"]
    [:link {:href "/output.css" :rel "stylesheet"}]
    [:script {:src "https://unpkg.com/htmx.org@2.0.4/dist/htmx.min.js" :defer true}]]
   [:body
    [:h1 "Welcome to Htmx + Kit module"]
    [:button.btn "Default"]]))