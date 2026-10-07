(ns modules.auth.mailer
  "Outbound email.

  A protocol rather than a direct provider call, so that signup and verification
  can be tested without a network or an API key, and so replacing the provider
  is a configuration change rather than an edit to the signup path.

  Nothing here is wired to a real provider yet. Until it is, LoggingMailer is
  what runs, and verification links are visible in the logs and nowhere else,
  which is the point at which a provider has to be chosen."
  (:require
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
  [_ {:keys [capture?] :or {capture? false}}]
  (if capture?
    (->RecordingMailer captured-messages-atom)
    (logging-mailer)))
