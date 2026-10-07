(ns core-test
  (:require [clj-http.client :as client]
            [clj-http.cookies :as cookies]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [app.core :as sut]
            [modules.auth.mailer :as mailer]
            [shared.config :as config])
  (:import (java.net ConnectException)))

(defn host-base-path [{:keys [port host]}]
  (format "http://%s:%s" host port))

(def stylesheet-built?
  "Whether the Tailwind bundle has been generated.

  resources/public/output.css is gitignored, so it only exists after
  `npm install && npm run tailwind`. CI builds it before running the suite, so
  the assertion below does run there; this keeps a local run without npm from
  failing on an artefact that was never built."
  (delay (some? (io/resource "public/output.css"))))

(defn- csrf-token
  "The value of the hidden anti-forgery input in a rendered page."
  [html]
  (some->> (re-seq #"<input[^>]*>" html)
           (filter #(str/includes? % "__anti-forgery-token"))
           first
           (re-find #"value=\"([^\"]+)\"")
           second))

(defn- verification-token
  "The one-time token the system's capturing mailer was asked to send."
  []
  (some-> (re-find #"https?://\S+"
                   (:body (last (mailer/captured-messages))))
          (str/split #"token=")
          second))

(defn- redirected-to?
  "Whether the request's redirect chain ended up at url.

  clj-http follows redirects and reports the final response as 200 (or 303
  turned into a GET), remembering where it went in :trace-redirects. The
  redirect itself is therefore asserted through its destination rather than
  its status."
  [resp url]
  (boolean (some #(str/includes? % url) (:trace-redirects resp))))

(deftest app-starts-and-stops-test
  (let [server-config (get-in (config/system-config {:profile :test}) [:server/http])
        base-path (host-base-path server-config)]
    (testing "basic app start and stop test"
      (sut/start-app {:opts {:profile :test}})
      (is (some? @sut/system))
      (is (= 200 (:status (client/get base-path))))
      (testing "public pages are served"
        (doseq [path ["/" "/auth/signin" "/auth/signup" "/auth/vendor-signup"]]
          (is (= 200 (:status (client/get (str base-path path))))
              (str path " should return 200"))))
      (testing "dashboard pages are guarded until signed in"
        (doseq [path ["/dashboard" "/vendor/dashboard"]]
          (is (redirected-to? (client/get (str base-path path)) "/auth/signin")
              (str path " should bounce anonymous visitors to sign in"))))
      (testing "static assets are served"
        (is (= 200 (:status (client/get (str base-path "/js/htmx.min.js")))))
        (if @stylesheet-built?
          (is (= 200 (:status (client/get (str base-path "/output.css"))))
              "output.css should be served once the Tailwind bundle is built")
          (println " [skip] output.css not built - run `npm install && npm run tailwind`")))
      (sut/stop-app)
      (is "Connection refused" (try (client/get base-path) (catch ConnectException ce (.getMessage ce)))))))

(deftest live-signup-and-verification-flow-test
  (let [server-config (get-in (config/system-config {:profile :test}) [:server/http])
        base-path (host-base-path server-config)
        email (str "live-" (System/currentTimeMillis) "@example.com")
        cookies (cookies/cookie-store)]
    (sut/start-app {:opts {:profile :test}})
    (mailer/reset-captured!)
    (let [signup-page (client/get (str base-path "/auth/signup")
                                  {:cookie-store cookies :as :text})
          token (csrf-token (:body signup-page))]
      (is (some? token) "the page hands out an anti-forgery token")
      (testing "a signup signs the browser in and points it at the dashboard"
        (let [resp (client/post (str base-path "/auth/signup")
                                {:cookie-store cookies
                                 :form-params {"__anti-forgery-token" token
                                               "name" "Live Ada"
                                               "email" email
                                               "password" "an-appropriate-password!"}})]
          (is (redirected-to? resp "/dashboard"))
          (let [dash (client/get (str base-path "/dashboard")
                                 {:cookie-store cookies :as :text})]
            (is (= 200 (:status dash)))
            (is (str/includes? (:body dash) "not verified yet")
                "the redirect carried the new session cookie"))))
      (testing "the emailed verification link verifies and clears the banner"
        (let [vt (verification-token)]
          (is (some? vt) "signup produced a verification message")
          (let [verify (client/get (str base-path "/auth/verify?token=" vt)
                                   {:cookie-store cookies})]
            (is (redirected-to? verify "/dashboard"))
            (let [dash (client/get (str base-path "/dashboard")
                                   {:cookie-store cookies :as :text})]
              (is (= 200 (:status dash)))
              (is (not (str/includes? (:body dash) "not verified yet"))
                  "the account is verified and the banner is gone"))))))
    (sut/stop-app)))

(deftest live-dev-verify-workaround-test
  (let [server-config (get-in (config/system-config {:profile :test}) [:server/http])
        base-path (host-base-path server-config)
        email (str "dev-verify-" (System/currentTimeMillis) "@example.com")
        cookies (cookies/cookie-store)]
    (sut/start-app {:opts {:profile :test}})
    (let [signup-page (client/get (str base-path "/auth/signup")
                                  {:cookie-store cookies :as :text})
          token (csrf-token (:body signup-page))]
      (client/post (str base-path "/auth/signup")
                   {:cookie-store cookies
                    :form-params {"__anti-forgery-token" token
                                  "name" "Dev Ada"
                                  "email" email
                                  "password" "an-appropriate-password!"}}))
    (testing "the dev stand-in verifies without a real mailer"
      (let [dash (client/get (str base-path "/dashboard")
                             {:cookie-store cookies :as :text})]
        (is (str/includes? (:body dash) "not verified yet"))
        (is (str/includes? (:body dash) "Simulate email click (dev)")
            "the button is offered in the test profile")
        (let [verify (client/post (str base-path "/auth/dev-verify")
                                  {:cookie-store cookies
                                   :form-params
                                   {"__anti-forgery-token" (csrf-token (:body dash))}})]
          ;; clj-http does not follow a 302 on a POST by itself (only 303), so
          ;; the redirect is asserted through its status and destination.
          (is (= 302 (:status verify)))
          (is (str/includes? (get-in verify [:headers "Location"]) "/dashboard"))
          (let [after (client/get (str base-path "/dashboard")
                                  {:cookie-store cookies :as :text})]
            (is (not (str/includes? (:body after) "not verified yet"))
                "the account is verified and the banner is gone")))))
    (sut/stop-app)))