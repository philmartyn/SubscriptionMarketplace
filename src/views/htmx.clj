(ns views.htmx
  (:require
   [hiccup.page :as p]
   [hiccup2.core :as h]
   [ring.util.http-response :as http-response]))

(defmacro page [opts & content]
  `(-> (p/html5 ~opts ~@content)
       http-response/ok
       (http-response/content-type "text/html")))

(defmacro pagelet [opts & content]
  `(-> (str (h/html ~opts ~@content))
       http-response/ok
       (http-response/content-type "text/html")))
