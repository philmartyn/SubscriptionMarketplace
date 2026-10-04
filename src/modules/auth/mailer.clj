(ns modules.auth.mailer
  "Outbound email.

  A protocol rather than a direct provider call, so that signup and verification
  can be tested without a network or an API key, and so replacing the provider
  is a configuration change rather than an edit to the signup path.

  Nothing here is wired to a real provider yet. Until it is, LoggingMailer is
  what runs, and verification links are visible in the logs and nowhere else,
  which is the point at which a provider has to be chosen."
  (:require
   [clojure.tools.logging :as log]))

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
