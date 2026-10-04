(ns views.landing-test
  "Tests for the marketing landing page.

  Several of these are regression tests for layout bugs that shipped once and
  were invisible in review: the hero buttons overflowing their container, and
  the delivery panel nested inside the header row."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [views.landing.content :as content]
   [views.landing.index :as landing]
   [views.landing.sections :as sections]
   [views.support :as sup]))

(defn- body
  "Body of a rendered page response."
  [resp]
  (:body resp))

(deftest landing-responds-with-html
  (let [resp (landing/landing nil)]
    (is (= 200 (:status resp)))
    (is (= "text/html" (get-in resp [:headers "Content-Type"])))))

(deftest landing-has-a-title-and-description
  (let [html (body (landing/landing nil))]
    (is (str/includes? html "SubMarket"))
    (is (str/includes? html "name=\"description\""))
    (is (= 1 (count (re-seq #"<title>" html))) "exactly one title, as the page needs")))

(deftest landing-has-exactly-one-h1
  (is (= 1 (count (re-seq #"<h1" (body (landing/landing nil)))))
      "one h1 per page, or screen reader users lose the page structure"))

(deftest landing-renders-every-section
  (let [html (body (landing/landing nil))]
    (doseq [id ["categories" "plans" "how-it-works" "for-business" "pricing" "faq"]]
      (is (str/includes? html (str "id=\"" id "\""))
          (str "section " id " should be on the page")))
    (is (str/includes? html (:title content/hero)) "hero heading")
    (is (str/includes? html (:title content/for-business)) "for-business pitch")
    (is (str/includes? html (:title content/final-cta)) "closing call to action")))

(deftest landing-shows-price-and-cadence-for-every-plan
  (let [html (body (landing/landing nil))]
    (doseq [{:keys [name price unit]} content/plans]
      (is (str/includes? html name) (str name " is listed"))
      (is (str/includes? html unit) (str unit " cadence shown for " name))
      (is (re-find (re-pattern (str price "\\b")) html)
          (str price " price shown for " name)))))

;;; the hero mock card

(deftest hero-actions-sit-inside-the-delivery-panel
  (let [tree (sections/hero)
        panel (sup/find-by-class tree "rounded-box")]
    (is (some? panel) "the white delivery panel is rendered")
    (when panel
      (is (sup/within? tree "rounded-box" "flex-1")
          "the action buttons must be inside the panel, not floating outside it")
      (let [buttons (filter #(= :button (sup/tag %)) (sup/elements panel))]
        (is (= ["Pause" "Skip"]
               (mapv #(str/trim (sup/text %)) buttons))
            "both actions live in the panel, in order")
        (is (every? #(sup/has-class? % "flex-1") buttons)
            "each action takes an equal share of the row")))))

(deftest hero-actions-are-not-full-width-buttons
  (let [tree (sections/hero)]
    (is (not-any? #(sup/has-class? % "btn-block")
                  (filter #(= :button (sup/tag %)) (sup/elements tree)))
        (str "btn-block is width:100% and .btn is flex-shrink:0, so two of "
             "them in a row overflow their container"))))

(deftest no-button-row-overflows-its-container
  (doseq [[name tree] {"hero" (sections/hero)
                       "plans" (sections/plans)
                       "pricing" (sections/pricing)
                       "final-cta" (sections/final-cta)
                       "categories" (sections/categories)
                       "how-it-works" (sections/how-it-works)
                       "for-business" (sections/for-business)
                       "testimonial" (sections/testimonial)
                       "faq" (sections/faq)}
          node (sup/elements tree)
          :let [row-btns (filter #(= :button (sup/tag %)) (sup/children node))]
          :when (< 1 (count row-btns))]
    (is (not-any? #(sup/has-class? % "btn-block") row-btns)
        (str name ": buttons sharing a row must not all be full width, they overflow"))))

;;; plans

(deftest plans-renders-a-card-per-listing
  (let [cards (filter #(sup/has-class? % "card") (sup/elements (sections/plans)))]
    (is (= (count content/plans) (count cards))
        "every plan in content should produce exactly one card")))

(deftest exactly-one-plan-is-featured
  (let [cards (filter #(sup/has-class? % "card") (sup/elements (sections/plans)))
        lifted (filter #(sup/has-class? % "lg:-mt-4") cards)]
    (is (= 1 (count lifted)) "only the featured plan is lifted")
    (when (seq lifted)
      (is (= "Daily Roast" (-> lifted first (sup/find-by-class "card-title") sup/text str/trim))
          "the lifted card is the featured one"))))

(deftest the-featured-plan-is-marked-as-such
  (let [html (body (landing/landing nil))]
    (is (str/includes? html "Most popular") "the featured plan carries a badge")))

;;; structure that must survive

(deftest faq-uses-native-disclosure-widgets
  (doseq [node (filter #(= :details (sup/tag %)) (sup/elements (sections/faq)))]
    (is (some #(= :summary (sup/tag %)) (sup/children node))
        "each FAQ entry needs a summary to be clickable"))
  (testing "no JavaScript is required for the FAQ to open"
    (is (not-any? #(= :input (sup/tag %))
                  (filter #(= :details (sup/tag %)) (sup/elements (sections/faq)))))))

(deftest the-page-is-server-rendered
  (let [html (body (landing/landing nil))]
    (is (str/includes? html "<section") "sections are in the markup, not built by JS")
    (is (re-find #"<h1[^>]*>Turn anything you sell" html)
        "the main pitch is real markup, readable without JavaScript")))

(deftest images-are-absent-so-nothing-shifts
  (is (not-any? #(= :img (sup/tag %)) (sup/elements (landing/landing nil)))
      "no <img> without dimensions; the page uses no raster images yet"))