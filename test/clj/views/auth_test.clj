(ns views.auth-test
  "Tests for the sign in and sign up pages.

  These are UI only. /api/auth/signin and /api/auth/signup are GET-only stubs
  returning nil, so nothing here asserts that submitting a form works; it
  asserts the markup carries the wiring such a form would need.

  Structural assertions run against the hiccup returned by auth-card, since a
  rendered page is a flat string by the time a handler returns it."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [views.auth :as auth]
   [views.support :as sup]))

(defn- body
  "Body of a rendered page response."
  [resp]
  (:body resp))

(defn- card
  "The shared card as hiccup, in the given mode: signin or signup."
  [mode]
  (auth/auth-card {:heading "Heading"
                   :subheading "Subheading"
                   :selected "email"
                   :mode mode}))

(defn- inputs
  "Every credential input in a card, by name.

  The provider radios are inputs too, but they are tabs rather than fields."
  [mode]
  (->> (sup/elements (card mode))
       (filter #(= :input (sup/tag %)))
       (remove #(= "provider" (get (sup/attrs %) :name)))
       (into {} (map (juxt #(get (sup/attrs %) :name) identity)))))

(deftest both-pages-respond-with-html
  (doseq [[name resp] {"signin" (auth/signin nil)
                       "signup" (auth/signup nil)}]
    (is (= 200 (:status resp)) (str name " returns 200"))
    (is (= "text/html" (get-in resp [:headers "Content-Type"]))
        (str name " is html"))
    (is (re-find #"(?i)<!doctype html>" (body resp)))))

(deftest both-pages-have-distinct-titles
  (let [signin (body (auth/signin nil))
        signup (body (auth/signup nil))]
    (is (str/includes? signin "<title>Sign in - SubMarket</title>"))
    (is (str/includes? signup "<title>Create an account - SubMarket</title>"))))

(deftest both-pages-are-titled-for-assistive-tech
  (doseq [[name resp] {"signin" (auth/signin nil)
                       "signup" (auth/signup nil)}]
    (is (= 1 (count (re-seq #"<h1" (body resp))))
        (str name " has exactly one h1"))
    (is (str/includes? (body resp) "name=\"description\"")
        (str name " has a meta description"))))

;;; forms

(deftest each-form-posts-to-its-own-endpoint
  (testing "signin"
    (let [html (body (auth/signin nil))]
      (is (str/includes? html "action=\"/auth/signin\""))
      (is (str/includes? html "hx-post=\"/api/auth/signin\""))))
  (testing "signup"
    (let [html (body (auth/signup nil))]
      (is (str/includes? html "action=\"/auth/signup\""))
      (is (str/includes? html "hx-post=\"/api/auth/signup\"")))))

(deftest forms-work-without-htmx
  (doseq [[name resp] {"signin" (auth/signin nil)
                       "signup" (auth/signup nil)}]
    (is (re-find #"<form[^>]*method=\"post\"" (body resp))
        (str name " uses a plain post, so it submits without JavaScript"))))

(deftest signin-asks-for-an-email-and-a-password
  (let [fields (inputs "signin")]
    (is (= #{"email" "password"} (set (keys fields)))
        "signin asks for exactly an email and a password")
    (doseq [[name input] fields]
      (is (:required (sup/attrs input))
          (str name " is required")))))

(deftest signup-also-asks-for-a-name
  (is (= #{"name" "email" "password"} (set (keys (inputs "signup"))))
      "signup collects a name, an email and a password"))

(deftest the-password-field-is-masked
  (doseq [mode ["signin" "signup"]]
    (is (= "password" (:type (sup/attrs (get (inputs mode) "password"))))
        "a password must not be echoed in cleartext")))

(deftest the-two-pages-cross-link
  (is (str/includes? (body (auth/signin nil)) "href=\"/auth/signup\"")
      "signin offers a route to signup")
  (is (str/includes? (body (auth/signup nil)) "href=\"/auth/signin\"")
      "signup offers a route back to signin"))

;;; provider tabs

(defn- providers
  "The provider radio inputs of a card."
  [mode]
  (->> (sup/elements (card mode))
       (filter #(and (= :input (sup/tag %))
                     (= "provider" (get (sup/attrs %) :name))))
       (mapv sup/attrs)))

(deftest the-provider-tabs-are-real-radio-inputs
  (doseq [mode ["signin" "signup"]]
    (let [radios (providers mode)]
      (is (= #{"email" "google" "apple"} (into #{} (map :value) radios))
          "email, Google and Apple are all offered")
      (is (every? #(= "radio" (:type %)) radios)
          "tabs are radios, so the form still submits if the script never runs"))))

(deftest email-is-selected-by-default
  (doseq [mode ["signin" "signup"]]
    (let [checked (filter :checked (providers mode))]
      (is (= 1 (count checked)) (str mode ": exactly one tab is checked"))
      (is (= "email" (:value (first checked))) (str mode ": email is preselected")))))

(deftest only-the-email-panel-is-shown-initially
  (doseq [mode ["signin" "signup"]]
    (let [panels (->> (sup/elements (card mode))
                      (filter #(:data-provider-panel (sup/attrs %)))
                      (mapv sup/attrs))
          visible (->> panels
                       (remove :hidden)
                       (mapv :data-provider-panel))]
      (is (= 1 (count panels)) (str mode ": only the email form is a panel"))
      (is (= ["email"] visible)
          (str mode ": the email panel is the one shown by default")))))

(deftest oauth-panels-offer-a-button-per-provider
  (let [html (body (auth/signin nil))]
    (is (str/includes? html "Continue with Google"))
    (is (str/includes? html "Continue with Apple"))))

;;; chrome

(deftest both-pages-get-the-shared-chrome
  (doseq [[name resp] {"signin" (auth/signin nil)
                       "signup" (auth/signup nil)}]
    (let [html (body resp)]
      (is (str/includes? html "class=\"navbar") (str name " has the navbar"))
      (is (str/includes? html "<footer") (str name " has the footer"))
      (is (str/includes? html "id=\"theme-toggle\"") (str name " has the theme toggle"))
      (is (str/includes? html "/js/htmx.min.js") (str name " loads htmx"))
      (is (str/includes? html "Skip to content") (str name " has a skip link")))))

(deftest the-provider-switch-script-binds-when-htmx-swaps-the-body
  (is (str/includes? auth/form-script "document.readyState")
      (str "htmx inserts the body then runs scripts, so DOMContentLoaded never "
           "fires again and the listener must bind on readyState instead")))