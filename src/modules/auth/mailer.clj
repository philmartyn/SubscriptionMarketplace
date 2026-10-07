(ns modules.auth.mailer
  "Outbound email.

  A protocol rather than a direct provider call, so that signup and verification
  can be tested without a network or an API key, and so replacing the provider
  is a configuration change rather than an edit to the signup path.

  Three implementations: LoggingMailer (no provider configured, so links stay
  in the logs), RecordingMailer (captures messages in memory for dev and test)
  and MailgunMailer (the real provider). The :auth/mailer Integrant component
  picks between them from configuration."
  (:require
   [clj-http.client :as http]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(defprotocol Mailer
  (send-email [this message]
    "Deliver one message.

  message has :to, :subject and :body. Implementations must not throw on a bad
  address: a signup that succeeded in the database should not be undone because
  the mail provider was down."))

(defrecord LoggingMailer [config]
  Mailer
  (send-email [_ {:keys [to subject] :as message}]
    (log/info "Email not sent: no provider configured"
              :to to
              :subject subject
              :config config)
    ;; The body carries the verification token, so it is deliberately not
    ;; logged. The signup path reports it instead, in development only.
    ::logged))

(defn logging-mailer
  []
  (->LoggingMailer {}))

(defrecord RecordingMailer [messages]
  Mailer
  (send-email [_ message]
    (swap! messages conj message)
    ::recorded))

(defn recording-mailer
  "A mailer that captures messages instead of sending them.

  Returns the mailer and the atom holding what it was asked to send."
  []
  (let [messages (atom [])]
    [(->RecordingMailer messages) messages]))

(defn sent-to?
  "Whether a captured message was addressed to email."
  [messages email]
  (boolean (some #(= email (:to %)) (if (instance? clojure.lang.IDeref messages)
                                      @messages
                                      messages))))

;;; Mailgun

;; The API is identical in both regions; only the host differs. The region is
;; fixed when the Mailgun account (and therefore each domain) is created.
(def ^:private region-base-url
  {:us "https://api.mailgun.net/v3"
   :eu "https://api.eu.mailgun.net/v3"})

(def ^:private request-timeout-ms 10000)

(defn- normalize-region
  [region]
  (let [region (if (nil? region) :us (keyword region))]
    (if (contains? region-base-url region) region :us)))

(defrecord MailgunMailer [api-key domain from region]
  Mailer
  (send-email [_ {:keys [to subject body]}]
    (let [region (normalize-region region)
          url    (str (get region-base-url region (:us region-base-url))
                      "/" domain "/messages")]
      (try
        ;; :throw-exceptions false turns a non-2xx into an ordinary response
        ;; rather than an exception, so the branches below are the only paths
        ;; out and the protocol's no-throw contract holds.
        (let [response (http/post
                        url
                        {:basic-auth         ["api" api-key]
                         :form-params        {:from    from
                                              :to      to
                                              :subject subject
                                              :text    body}
                         :throw-exceptions   false
                         :socket-timeout     request-timeout-ms
                         :connection-timeout request-timeout-ms})
              status   (:status response)]
          (if (<= 200 status 299)
            (do (log/info "Mailgun accepted a message" {:to to :status status})
                ::sent)
            (do (log/error "Mailgun rejected a message"
                           {:to to :status status :response (:body response)})
                ::failed)))
        (catch Exception e
          (log/error e "Mailgun request failed" {:to to})
          ::failed)))))

(defn mailgun-mailer
  "A Mailgun sender.

  Callers pass the configuration; nothing is read here, so tests can point it
  at a stub. :region is :us or :eu (a string is accepted too)."
  [{:keys [api-key domain from region]}]
  (->MailgunMailer api-key domain from (normalize-region region)))

;;; component

;; Where the system's mailer puts what it sends while capture? is on, so a
;; live HTTP test can reach the verification link that a real provider would
;; have delivered.
(defonce ^:private captured-messages-atom (atom []))

(defn captured-messages
  "Everything the system's mailer has been asked to send since boot or the
  last reset. Messages carry :to, :subject and :body - the body holds the
  verification url."
  []
  @captured-messages-atom)

(defn reset-captured!
  []
  (reset! captured-messages-atom []))

(defmethod ig/init-key :auth/mailer
  [_ {:keys [capture? api-key domain from region] :or {capture? false}}]
  (if capture?
    (->RecordingMailer captured-messages-atom)
    (do
      ;; Fail fast rather than fall back to LoggingMailer: a production
      ;; instance that cannot send verification mail must not boot quietly and
      ;; leave every signup unverifiable.
      (when (or (str/blank? api-key) (str/blank? domain))
        (throw (ex-info
                "Mailgun is not configured: set MAILGUN_API_KEY and MAILGUN_DOMAIN"
                {:missing (cond-> []
                            (str/blank? api-key) (conj :api-key)
                            (str/blank? domain)  (conj :domain))})))
      (mailgun-mailer {:api-key api-key
                       :domain  domain
                       :from    from
                       :region  region}))))