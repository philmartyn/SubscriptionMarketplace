(ns views.landing.index
  "The marketing landing page served at /."
  (:require
   [views.layout :as layout]
   [views.landing.content :as content]
   [views.landing.sections :as sections]))

(defn landing
  "GET / handler."
  [_request]
  (layout/page
   {:title (str layout/site-name " - turn anything you sell into a subscription")
    :description (:body content/hero)}
   (sections/hero)
   (sections/trust-bar)
   (sections/categories)
   (sections/plans)
   (sections/how-it-works)
   (sections/for-business)
   (sections/pricing)
   (sections/testimonial)
   (sections/faq)
   (sections/final-cta)))