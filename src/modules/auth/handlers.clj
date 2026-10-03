(ns modules.auth.handlers
  (:require [cheshire.core :as json]
            [clj-http.client :as client]))

(defn authenticate [req] ;; JWT or session check
  )

;Mail Gun Api key - 4bc11bbfd9a8503f5959bd42b4300495-a1dad75f-7a905cfc

(defn signup [{:keys [] :as req}]
  ;; take the email and password and install user in the DB
  #_(client/post "http://localhost:3567/recipe/signup"
                 {:headers {"Content-Type" "application/json; charset=utf-8"}
                  :body (json/generate-string {:email "test@test.com" :password "Test"})})

  ;; send email verification email

;; return session data to the frontend
  )