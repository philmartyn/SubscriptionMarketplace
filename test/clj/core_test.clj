(ns core-test
  (:require [clj-http.client :as client]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [app.core :as sut]
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

(deftest app-starts-and-stops-test
  (let [server-config (get-in (config/system-config {:profile :test}) [:server/http])
        base-path (host-base-path server-config)]
    (testing "basic app start and stop test"
      (sut/start-app {:opts {:profile :test}})
      (is (some? @sut/system))
      (is (= 200 (:status (client/get base-path))))
      (testing "landing and auth pages are served"
        (doseq [path ["/" "/dashboard" "/auth/signin" "/auth/signup"]]
          (is (= 200 (:status (client/get (str base-path path))))
              (str path " should return 200"))))
      (testing "static assets are served"
        (is (= 200 (:status (client/get (str base-path "/js/htmx.min.js")))))
        (if @stylesheet-built?
          (is (= 200 (:status (client/get (str base-path "/output.css"))))
              "output.css should be served once the Tailwind bundle is built")
          (println " [skip] output.css not built - run `npm install && npm run tailwind`")))
      (sut/stop-app)
      (is "Connection refused" (try (client/get base-path) (catch ConnectException ce (.getMessage ce)))))))