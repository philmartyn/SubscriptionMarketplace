(ns modules.auth.mailer-test
  "The Mailgun provider and the :auth/mailer component.

  The HTTP boundary is stubbed with clj-http's post replaced, so nothing here
  touches the network: the request is asserted by its shape and the responses
  are synthesised."
  (:require
   [clj-http.client :as http]
   [clojure.test :refer [deftest is testing]]
   [integrant.core :as ig]
   [modules.auth.mailer :as mailer]))

(def ^:private config
  {:api-key "key-secret"
   :domain  "sandbox.example.mailgun.org"
   :from    "SubMarket <verify@sandbox.example.mailgun.org>"
   :region  :us})

(def ^:private message
  {:to      "ada@example.com"
   :subject "Confirm your email address"
   :body    "https://example.test/auth/verify?token=abc"})

(defn- sending
  "Call send with clj-http's post replaced by a stub returning response.
  Returns {:result ... :seen [url opts]}."
  [response send]
  (let [seen (atom nil)
        result (with-redefs [http/post (fn [url opts]
                                         (reset! seen [url opts])
                                         response)]
                 (send))]
    {:result result :seen @seen}))

;;; request shape

(deftest mailgun-posts-to-the-region-endpoint-with-basic-auth
  (let [m (mailer/mailgun-mailer config)
        {:keys [result seen]} (sending {:status 200 :body "{}"}
                                       #(mailer/send-email m message))
        [url opts] seen]
    (is (= ::mailer/sent result))
    (is (= "https://api.mailgun.net/v3/sandbox.example.mailgun.org/messages" url))
    (is (= ["api" "key-secret"] (:basic-auth opts)))
    (is (false? (:throw-exceptions opts))
        "a non-2xx is handled as a response, never an exception")
    (is (= {:from    "SubMarket <verify@sandbox.example.mailgun.org>"
            :to      "ada@example.com"
            :subject "Confirm your email address"
            :text    "https://example.test/auth/verify?token=abc"}
           (:form-params opts))
        "the message body travels as Mailgun's :text field")))

(deftest mailgun-sends-to-the-eu-host-when-asked
  (let [m (mailer/mailgun-mailer (assoc config :region "eu"))
        {:keys [seen]} (sending {:status 200 :body "{}"}
                                #(mailer/send-email m message))
        [url _] seen]
    (is (= "https://api.eu.mailgun.net/v3/sandbox.example.mailgun.org/messages"
           url))))

(deftest an-unknown-region-falls-back-to-us
  (let [m (mailer/mailgun-mailer (assoc config :region "mars"))
        {:keys [seen]} (sending {:status 200 :body "{}"}
                                #(mailer/send-email m message))
        [url _] seen]
    (is (= "https://api.mailgun.net/v3/sandbox.example.mailgun.org/messages"
           url))))

;;; the no-throw contract

(deftest a-rejected-message-is-reported-not-thrown
  (let [m (mailer/mailgun-mailer config)]
    (is (= ::mailer/failed
           (:result (sending {:status 400 :body "bad request"}
                             #(mailer/send-email m message)))))))

(deftest a-provider-error-is-reported-not-thrown
  (let [m (mailer/mailgun-mailer config)]
    (is (= ::mailer/failed
           (:result (sending {:status 500 :body "boom"}
                             #(mailer/send-email m message)))))))

(deftest a-network-failure-is-reported-not-thrown
  (let [m (mailer/mailgun-mailer config)]
    (with-redefs [http/post (fn [_ _]
                              (throw (java.io.IOException. "connection refused")))]
      (is (= ::mailer/failed (mailer/send-email m message))))))

;;; the component

(deftest the-component-captures-when-capture-is-on
  (is (instance? modules.auth.mailer.RecordingMailer
                 (ig/init-key :auth/mailer {:capture? true}))
      "dev/test capture does not need credentials"))

(deftest the-component-builds-a-mailgun-sender-in-production
  (let [m (ig/init-key :auth/mailer {:capture? false
                                     :api-key "key-secret"
                                     :domain  "sandbox.example.mailgun.org"
                                     :from    "SubMarket <verify@sandbox.example.mailgun.org>"
                                     :region  "us"})]
    (is (instance? modules.auth.mailer.MailgunMailer m))
    (is (= "key-secret" (:api-key m)))
    (is (= :us (:region m)) "a string region is normalised to a keyword")))

(deftest the-component-fails-fast-without-credentials
  (testing "no key"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Mailgun is not configured"
                          (ig/init-key :auth/mailer
                                       {:capture? false
                                        :domain   "sandbox.example.mailgun.org"}))))
  (testing "no domain"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Mailgun is not configured"
                          (ig/init-key :auth/mailer
                                       {:capture? false
                                        :api-key  "key-secret"}))))
  (testing "a blank key counts as missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Mailgun is not configured"
                          (ig/init-key :auth/mailer
                                       {:capture? false
                                        :api-key  "   "
                                        :domain   "sandbox.example.mailgun.org"})))))