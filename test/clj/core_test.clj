(ns core-test
  (:require [clj-http.client :as client]
            [clojure.test :refer :all]
            [app.core :as sut])
  (:import (java.net ConnectException)))

(deftest app-starts-and-stops-test
  (testing "basic app start and stop test"
    (let []
      (sut/start-app {:profile :dev})
      (is (some? @sut/system))
      (is (= 200 (:status (client/get "http://localhost:3000"))))
      (sut/stop-app)
      (is "Connection refused" (try (client/get "http://localhost:3000") (catch ConnectException ce (.getMessage ce)))))))