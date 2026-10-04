(ns views.layout-test
  "Tests for the shared page shell.

  These cover the two regressions that actually shipped broken: hx-boost being
  dropped from hash links because Hiccup discards boolean false, and a
  stylesheet URL with no cache busting."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [views.auth :as auth]
   [views.layout :as layout]
   [views.landing.index :as landing]
   [views.routes :as routes]
   [views.support :as sup]))

(defn- body
  "Body of a rendered page response."
  [resp]
  (:body resp))

;;; cache busting

(deftest css-href-is-cache-busted
  (let [[file query] (str/split (layout/css-href) #"\?")]
    (is (= "/output.css" file))
    (is (re-matches #"v=\d+" query)
        (str "version should look like v=<timestamp>, got " query))))

(deftest css-href-tracks-the-bundle-timestamp
  (let [mtime (some-> (io/resource "public/output.css") .openConnection .getLastModified)
        href (layout/css-href)]
    (is (str/starts-with? href "/output.css?v="))
    (when mtime
      (is (str/ends-with? href (str "?v=" mtime))
          "a rebuild must produce a new URL, or browsers keep serving stale CSS"))))

(deftest css-href-falls-back-when-the-bundle-is-missing
  (with-redefs [io/resource (constantly nil)]
    (is (= "/output.css?v=0" (layout/css-href))
        "a missing bundle must not throw on a fresh clone")))

;;; hx-boost

(deftest hash-links-opt-out-of-hx-boost
  (testing "in-page anchors must scroll, not trigger a full body swap"
    (is (= {:href "/#plans" :hx-boost "false"} (layout/anchor-attrs "/#plans"))))
  (testing "the value must be a string: Hiccup drops attributes set to boolean false"
    (is (string? (:hx-boost (layout/anchor-attrs "/#plans")))
        "boolean false would be omitted entirely and the link would stay boosted"))
  (testing "ordinary links inherit hx-boost from the body"
    (is (= {:href "/auth/signin"} (layout/anchor-attrs "/auth/signin")))))

;;; document shell

(deftest page-renders-a-complete-document
  (let [resp (layout/page {} [:p "hello"])]
    (is (= 200 (:status resp)))
    (is (= "text/html" (get-in resp [:headers "Content-Type"])))
    (is (re-find #"(?i)<!doctype html>" (body resp)))
    (is (str/includes? (body resp) "<html lang=\"en\">"))
    (is (str/includes? (body resp) "<p>hello</p>"))))

(deftest page-wires-up-htmx-locally
  (let [html (body (layout/page {}))]
    (testing "htmx is vendored under resources/public, not pulled from a CDN"
      (is (re-find #"<script[^>]*src=\"/js/htmx\.min\.js\"" html)))
    (testing "htmx is deferred so it does not block first paint"
      (is (re-find #"<script[^>]*defer[^>]*src=\"/js/htmx\.min\.js\"" html)))
    (testing "the vendored file is actually on the classpath"
      (is (some? (io/resource "public/js/htmx.min.js"))))))

(deftest page-enables-hx-boost-on-the-body
  (let [html (body (layout/page {}))]
    (is (re-find #"<body[^>]*hx-boost=\"true\"" html)
        "links are boosted page-wide, so individual hash links must opt out")))

(deftest page-links-a-cache-busted-stylesheet
  (is (re-find #"<link[^>]*href=\"/output\.css\?v=\d+\"" (body (layout/page {})))))

(deftest page-includes-the-shared-chrome
  (let [html (body (layout/page {} [:p "x"]))]
    (testing "a skip link is the first focusable thing, for keyboard users"
      (is (str/includes? html "Skip to content"))
      (is (str/includes? html "href=\"#main\"")))
    (testing "the main landmark the skip link targets exists"
      (is (re-find #"<main[^>]*id=\"main\"" html)))
    (testing "navbar and footer wrap the page content"
      (is (str/includes? html "class=\"navbar"))
      (is (str/includes? html "<footer")))
    (testing "the brand name is shown"
      (is (str/includes? html layout/site-name)))))

(deftest page-offers-a-theme-toggle
  (let [html (body (layout/page {}))]
    (is (str/includes? html "id=\"theme-toggle\""))
    (is (str/includes? html "id=\"icon-sun\""))
    (is (str/includes? html "id=\"icon-moon\""))))

;;; head metadata

(deftest page-title-falls-back-to-the-site-name
  (is (str/includes? (body (layout/page {}))
                     (str "<title>" layout/site-name))))

(deftest page-uses-an-explicit-title-when-given
  (is (str/includes? (body (layout/page {:title "Plans"})) "<title>Plans</title>")))

(deftest page-description-is-optional
  (testing "omitted when absent, so pages do not ship an empty meta tag"
    (is (not (str/includes? (body (layout/page {})) "name=\"description\""))))
  (testing "rendered when present"
    (is (str/includes? (body (layout/page {:description "Words"})) "content=\"Words\""))))

(deftest page-can-pin-a-theme
  (testing "absent, the stored or system preference wins"
    (is (not (str/includes? (body (layout/page {})) "localStorage.setItem('submarket-theme','dark')"))))
  (testing "requested, it is written before paint so there is no flash"
    (is (str/includes? (body (layout/page {:theme "dark"}))
                       "localStorage.setItem('submarket-theme','dark')"))))

;;; theme script

(deftest theme-script-sets-the-theme-before-paint
  (let [s layout/theme-script]
    (is (str/includes? s "document.documentElement"))
    (is (str/includes? s "prefers-color-scheme: dark")
        "the system preference is the fallback when nothing is stored")
    (is (str/includes? s "localStorage"))))

(deftest theme-script-is-guarded-against-rebinding
  (is (str/includes? layout/theme-script "window.submarketThemeBound")
      "hx-boost body swaps re-run inline scripts, which would stack up listeners"))

;;; links

(deftest nav-and-footer-anchor-links-point-at-real-sections
  ;; Checked against the landing page, since that is the only page carrying the
  ;; anchored sections.
  (let [ids (sup/ids-in (body (landing/landing nil)))
        broken (->> (concat (map #(str "/#" (:id %)) layout/nav-sections)
                            (mapcat (comp :href :links) layout/footer-columns))
                    (filter #(not (contains? ids (second (str/split % #"#")))))
                    (into []))]
    (is (empty? broken)
        (str "no section on the page for these anchors: " (pr-str broken)))))

(deftest no-page-links-to-a-route-that-does-not-exist
  (let [known (into #{} (map first) (routes/ui-routes nil))
        ;; TODO /terms and /privacy are advertised in the footer but have no
        ;; route yet. Delete them from here once the pages exist.
        known-gaps #{"/terms" "/privacy"}
        pages {"landing" (body (landing/landing nil))
               "signin" (body (auth/signin nil))
               "signup" (body (auth/signup nil))}
        dead (->> (for [[page html] pages
                        href (sup/hrefs html)
                        :when (and (str/starts-with? href "/") (not= href "/"))
                        :let [path (first (str/split href #"[?#]"))]
                        :when (seq path)]
                    [page href path])
                  (remove (fn [[_ _ path]] (or (contains? known path)
                                               (contains? known-gaps path))))
                  (into []))]
    (is (empty? dead)
        (str "these links 404: " (pr-str dead)))))

(deftest no-malformed-attributes-in-the-markup
  ;; A typo like {:class= "..."} is still a valid keyword, so neither clj-kondo
  ;; nor cljfmt catches it. Hiccup renders it as class==="...", which silently
  ;; drops every style the attribute was supposed to carry.
  (doseq [[page resp] {"landing" (landing/landing nil)
                       "signin" (auth/signin nil)
                       "signup" (auth/signup nil)}]
    (is (nil? (re-find #"\s[a-zA-Z-]+==\"" (body resp)))
        (str page " contains a malformed attribute"))))