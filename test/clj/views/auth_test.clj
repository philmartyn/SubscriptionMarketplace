(ns views.auth-test
  "Tests for the sign in, sign up and vendor sign up pages.

  These cover the markup: what each form posts to, what it collects, and the
  CSRF field. The behaviour of the endpoints is covered by
  modules.auth.handlers-test; a page test here stays a page test."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [ring.middleware.anti-forgery :as anti-forgery]
   [views.auth :as auth]
   [views.support :as sup]))

(defn- body
  "Body of a rendered page response."
  [resp]
  (:body resp))

(defn- card
  "The shared card as hiccup, in the given mode."
  [mode]
  (auth/auth-card {:mode mode}))

(defn- inputs
  "Every form input in a card, by name."
  [mode]
  (->> (sup/elements (card mode))
       (filter #(= :input (sup/tag %)))
       (into {} (map (juxt #(get (sup/attrs %) :name) identity)))))

(def pages
  "The three auth pages and the titles they advertise."
  {"signin"        {:resp (auth/signin nil)
                    :title "Sign in - SubMarket"}
   "signup"        {:resp (auth/signup nil)
                    :title "Create an account - SubMarket"}
   "vendor-signup" {:resp (auth/vendor-signup nil)
                    :title "Vendor signup - SubMarket"}})

(deftest every-page-responds-with-html
  (doseq [[name {:keys [resp]}] pages]
    (is (= 200 (:status resp)) (str name " returns 200"))
    (is (= "text/html" (get-in resp [:headers "Content-Type"]))
        (str name " is html"))
    (is (re-find #"(?i)<!doctype html>" (body resp)))))

(deftest every-page-has-a-distinct-title
  (doseq [[name {:keys [resp title]}] pages]
    (is (str/includes? (body resp) (str "<title>" title "</title>"))
        (str name " is titled " title))))

(deftest every-page-is-titled-for-assistive-tech
  (doseq [[name {:keys [resp]}] pages]
    (is (= 1 (count (re-seq #"<h1" (body resp))))
        (str name " has exactly one h1"))
    (is (str/includes? (body resp) "name=\"description\"")
        (str name " has a meta description"))))

;;; forms

(deftest each-form-posts-to-its-own-endpoint
  (doseq [[name {:keys [resp]}] (select-keys pages ["signin" "signup" "vendor-signup"])
          :let [html (body resp)
                endpoint (case name
                           "signin" "/auth/signin"
                           "signup" "/auth/signup"
                           "vendor-signup" "/auth/vendor-signup")]]
    (is (str/includes? html (str "action=\"" endpoint "\""))
        (str name " posts to " endpoint))
    (is (str/includes? html (str "hx-post=\"" endpoint "\""))
        (str name " swaps the same endpoint via htmx"))))

(deftest forms-submit-without-javascript
  (doseq [[name {:keys [resp]}] pages]
    (is (re-find #"<form[^>]*method=\"post\"" (body resp))
        (str name " uses a plain post, so it submits without JavaScript"))))

(deftest signin-asks-for-an-email-and-a-password
  (let [fields (inputs :signin)]
    (is (= #{"email" "password"} (set (keys fields)))
        "signin asks for exactly an email and a password")
    (doseq [[name input] fields]
      (is (:required (sup/attrs input))
          (str name " is required")))))

(deftest the-signup-pages-also-ask-for-a-name
  (doseq [mode [:signup :vendor-signup]]
    (is (= #{"name" "email" "password"} (set (keys (inputs mode))))
        (str mode " collects a name, an email and a password"))))

(deftest the-password-field-is-masked
  (doseq [mode [:signin :signup :vendor-signup]]
    (is (= "password" (:type (sup/attrs (get (inputs mode) "password"))))
        "a password must not be echoed in cleartext")))

(deftest the-pages-cross-link
  (is (str/includes? (body (auth/signin nil)) "href=\"/auth/signup\""))
  (is (str/includes? (body (auth/signin nil)) "href=\"/auth/vendor-signup\""))
  (is (str/includes? (body (auth/signup nil)) "href=\"/auth/signin\""))
  (is (str/includes? (body (auth/vendor-signup nil)) "href=\"/auth/signin\""))
  (is (str/includes? (body (auth/vendor-signup nil)) "href=\"/auth/signup\"")
      "a vendor can duck out of the vendor flow into the subscriber one"))

(deftest forms-carry-a-csrf-token-when-one-is-bound
  ;; Rendered inside the binding: the pages def above is evaluated at load
  ;; time, before any test binding could be in effect.
  (binding [anti-forgery/*anti-forgery-token* "page-token"]
    (doseq [[name render] [["signin" auth/signin]
                           ["signup" auth/signup]
                           ["vendor-signup" auth/vendor-signup]]
            :let [html (body (render nil))]]
      (is (str/includes? html "name=\"__anti-forgery-token\"")
          (str name " renders the anti-forgery field"))
      (is (str/includes? html "value=\"page-token\"")
          (str name " renders the token the session knows"))))
  (is (not (str/includes? (body (auth/signin nil)) "__anti-forgery-token"))
      "outside a request no token is bound, and no input is rendered"))

;;; errors

(deftest field-errors-render-next-to-their-input
  (let [html (body (auth/page {:mode :signup
                               :errors {:email "That does not look like an email address."}}))]
    (is (str/includes? html "That does not look like an email address."))))

(deftest a-form-level-error-renders-inside-the-form
  (let [html (body (auth/page {:mode :signin
                               :errors {:credentials "That email and password do not match."}}))]
    (is (str/includes? html "That email and password do not match."))))

(deftest submitted-values-are-echoed-back-into-the-form
  (let [html (body (auth/page {:mode :signup
                               :values {:email "ada@example.com"}}))]
    (is (str/includes? html "value=\"ada@example.com\""))))

(deftest the-signin-page-documents-an-invalid-verification-link
  (let [html (body (auth/signin {:params {:verify "invalid"}}))]
    (is (str/includes? html "invalid or has already been used."))))

;;; no providers

(deftest there-is-no-provider-plumbing
  (let [html (body (auth/signin nil))]
    (is (not (str/includes? html "Continue with Google")))
    (is (not (str/includes? html "Continue with Apple")))
    (is (not (str/includes? html "provider-tabs")))
    (is (not (str/includes? html "data-provider-panel")))))

;;; chrome

(deftest every-page-gets-the-shared-chrome
  (doseq [[name {:keys [resp]}] pages]
    (let [html (body resp)]
      (is (str/includes? html "class=\"navbar") (str name " has the navbar"))
      (is (str/includes? html "<footer") (str name " has the footer"))
      (is (str/includes? html "id=\"theme-toggle\"") (str name " has the theme toggle"))
      (is (str/includes? html "/js/htmx.min.js") (str name " loads htmx"))
      (is (str/includes? html "Skip to content") (str name " has a skip link")))))