(ns views.dashboard-test
  "The subscriber and vendor dashboards - the pages the route guards protect."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [ring.middleware.anti-forgery :as anti-forgery]
   [views.dashboard.home :as home]
   [views.dashboard.vendor :as vendor]))

(defn- body
  [resp]
  (:body resp))

;;; subscriber dashboard

(deftest the-subscriber-dashboard-asks-for-verification-while-pending
  (let [resp (home/home {:session {:account-id 1 :verified? false :account-type :user}})
        html (body resp)]
    (is (= 200 (:status resp)))
    (is (str/includes? html "not verified yet"))
    (is (str/includes? html "Resend verification email"))
    (is (str/includes? html "hx-post=\"/auth/resend-verification\""))
    (is (str/includes? html "Sign out"))
    (is (str/includes? html "hx-post=\"/auth/signout\""))))

(deftest a-verified-account-sees-no-verification-banner
  (let [html (body (home/home {:session {:account-id 1
                                         :verified? true
                                         :account-type :user}}))]
    (is (not (str/includes? html "not verified yet")))
    (is (not (str/includes? html "Resend verification email")))))

(deftest a-vendor-sees-a-link-to-its-own-dashboard
  (let [html (body (home/home {:session {:account-id 1
                                         :verified? true
                                         :account-type :vendor}}))]
    (is (str/includes? html "href=\"/vendor/dashboard\""))))

(deftest the-dev-verify-button-appears-only-when-the-request-enables-it
  (let [banner (fn [request] (body (home/home request)))
        on     (banner {:session {:account-id 1 :verified? false}
                        :system  {:config {:dev-verify? true}}})
        off    (banner {:session {:account-id 1 :verified? false}})]
    (is (str/includes? on "Simulate email click (dev)"))
    (is (str/includes? on "hx-post=\"/auth/dev-verify\""))
    (is (not (str/includes? off "Simulate email click (dev)"))
        "the workaround is invisible unless the request turns it on")
    (is (not (str/includes? (banner {:session {:account-id 1 :verified? true}
                                     :system  {:config {:dev-verify? true}}})
                            "Simulate email click (dev)"))
        "a verified account has no verification banner to work around")))

;;; vendor dashboard

(deftest the-vendor-dashboard-is-a-stub
  (let [resp (vendor/home {:session {:account-id 1
                                     :verified? true
                                     :account-type :vendor}})
        html (body resp)]
    (is (= 200 (:status resp)))
    (is (str/includes? html "Vendor dashboard"))
    (is (str/includes? html "Plans, orders and payouts will live here."))
    (is (str/includes? html "Sign out"))))

(deftest the-vendor-dashboard-asks-for-verification-while-pending
  (let [html (body (vendor/home {:session {:account-id 1
                                           :verified? false
                                           :account-type :vendor}}))]
    (is (str/includes? html "not verified yet"))))

;;; csrf on the forms

(deftest the-dashboard-forms-carry-a-csrf-token-when-one-is-bound
  (binding [anti-forgery/*anti-forgery-token* "page-token"]
    (doseq [resp [(home/home {:session {:verified? true}})
                  (vendor/home {:session {:verified? true}})]]
      (is (str/includes? (body resp) "name=\"__anti-forgery-token\""))
      (is (str/includes? (body resp) "value=\"page-token\""))))
  (is (not (str/includes? (body (home/home {:session {:verified? true}}))
                          "__anti-forgery-token"))
      "outside a request no token is bound, and no input is rendered"))