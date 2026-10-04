(ns views.stylesheet-test
  "Checks the generated Tailwind bundle against the markup it has to style.

  output.css is a build artifact and is gitignored, so on a fresh clone or in CI
  this namespace registers no tests and says so rather than failing. Run
  npm install && npm run tailwind first."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [views.auth :as auth]
   [views.landing.index :as landing]
   [views.support :as sup]))

(def stylesheet
  "The built bundle, or nil when it has not been built."
  (delay
    (some-> (io/resource "public/output.css") slurp)))

(defn- pages
  "Every page a visitor can reach, as rendered HTML."
  []
  {"landing" (:body (landing/landing nil))
   "signin" (:body (auth/signin nil))
   "signup" (:body (auth/signup nil))})

(when-not @stylesheet
  (println " [skip] output.css not built - run `npm install && npm run tailwind`"
           "to run views.stylesheet-test"))

(when @stylesheet
  (deftest every-class-used-in-the-markup-has-a-rule
    (let [css @stylesheet
          used (into #{} (mapcat sup/classes-in) (vals (pages)))
          missing (->> used
                       (remove #(str/includes? css (sup/css-selector %)))
                       (sort)
                       (into []))]
      (is (empty? missing)
          (str (count missing) " of " (count used) " classes have no rule in output.css: "
               (pr-str (take 20 missing))))))

  (deftest the-bundle-defines-both-themes
    (let [css @stylesheet]
      (is (str/includes? css "[data-theme=light]") "light theme variables are emitted")
      (is (str/includes? css "[data-theme=dark]") "dark theme variables are emitted")
      (is (str/includes? css "--color-primary") "daisyUI theme colours are emitted")))

  (deftest the-bundle-defines-the-theme-icons
    (let [css @stylesheet]
      (is (str/includes? css "icon-sun") "the sun icon is styled")
      (is (str/includes? css "icon-moon") "the moon icon is styled"))))