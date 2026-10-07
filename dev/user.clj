(ns user
  "REPL helpers for development.

  Migrations are no longer wired up from here. They run either through
  `clojure -M:db migrate` or automatically at boot via the :migrate? flag in
  system.edn, which is enabled for :dev and :test only."
  (:require
   [app.core]
   [app.handler]
   [clojure.string :as str]
   [clojure.spec.alpha :as s]
   [expound.alpha :as expound]
   [clojure.tools.namespace.repl :as repl]
   [integrant.core :as ig]
   [clojure.tools.namespace.repl :as tools]
   [components.db.core]
   [integrant.repl :as ig-repl :refer [clear go halt init prep reset reset-all]]
   [modules.auth.mailer :as mailer]
   [lambdaisland.classpath.watch-deps :as watch-deps]
   [shared.config :as config]))

(defn reset-app
  "Stop the system and start it again, picking up changed namespaces."
  []
  (reset))

(defn send-test-email!
  "Send one real message through Mailgun, bypassing the capture profile.

  Reads MAILGUN_API_KEY from the environment; MAILGUN_DOMAIN, MAILGUN_FROM and
  MAILGUN_REGION fall back to the test sandbox, so exporting the key is enough:

      MAILGUN_API_KEY=... clojure -M:dev
      user=> (send-test-email! \"phil.martyn@gmail.com\")

  The recipient must be an authorised recipient on the sandbox domain in the
  Mailgun dashboard. Returns ::modules.auth.mailer/sent or .../failed - the
  same values the real provider returns in production."
  [to]
  (let [api-key (System/getenv "MAILGUN_API_KEY")]
    (when (str/blank? api-key)
      (throw (ex-info "Set MAILGUN_API_KEY in the shell first" {})))
    (mailer/send-email
     (mailer/mailgun-mailer
      {:api-key api-key
       :domain  (or (System/getenv "MAILGUN_DOMAIN")
                    "sandbox35c210fabdb84877a42940866a26cf36.mailgun.org")
       :from    (or (System/getenv "MAILGUN_FROM")
                    "SubMarket <verify@sandbox35c210fabdb84877a42940866a26cf36.mailgun.org>")
       :region  (or (System/getenv "MAILGUN_REGION") :us)})
     {:to      to
      :subject "SubMarket test email"
      :body    (str "If you can read this, the Mailgun wiring works.\n\n"
                    "This is a manual smoke test, not a real verification "
                    "message.")})))


;; uncomment to enable hot loading for deps
(watch-deps/start! {:aliases [:dev :test]})

(alter-var-root #'s/*explain-out* (constantly expound/printer))

(add-tap (bound-fn* clojure.pprint/pprint))

(defn dev-prep!
  []
  (integrant.repl/set-prep! (fn []
                              (-> (shared.config/system-config {:profile :dev})
                                  (ig/expand)))))

(defn test-prep!
  []
  (integrant.repl/set-prep! (fn []
                              (-> (shared.config/system-config {:profile :test})
                                  (ig/expand)))))

;; Can change this to test-prep! if want to run tests as the test profile in your repl
;; You can run tests in the dev profile, too, but there are some differences between
;; the two profiles.
(dev-prep!)

(repl/set-refresh-dirs "src/clj")

(def refresh repl/refresh)


;; Useful at the REPL:
;;
;;   (start-app)          boot on port 3000
;;   (start-app :opts {:env :dev :port 3100})   boot on another port
;;   (stop-app)           shut down
;;
;; To explore the database:
;;
;;   (psql postgresql://admin:1234@localhost:5432/sub_market_test)
