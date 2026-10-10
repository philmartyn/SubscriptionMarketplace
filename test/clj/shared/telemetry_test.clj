(ns shared.telemetry-test
  "The invariant the rest of the app leans on: telemetry is a no-op when no
  agent is attached.

  These run on the plain test classpath, with no agent and no collector, which
  is exactly the situation the guard protects. If a telemetry call ever throws
  without the agent, the domain tests would fail for reasons that look nothing
  like telemetry, so the contract is asserted directly here instead."
  (:require
   [clojure.test :refer [deftest is testing]]
   [shared.telemetry :as telemetry])
  (:import
   (io.opentelemetry.api.common AttributeKey)))

(deftest with-span-returns-the-body-value
  (is (= :done (telemetry/with-span "test.value" {} :done)))
  (is (= {:ok? true} (telemetry/with-span "test.value" {:k "v"} {:ok? true}))))

(deftest with-span-propagates-exceptions
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                        (telemetry/with-span "test.throws" {} (throw (ex-info "boom" {}))))))

(deftest with-span-is-safe-without-an-agent
  (testing "nil attributes and a nil body result are both fine"
    (is (nil? (telemetry/with-span "test.nil" nil nil)))
    (is (nil? (telemetry/with-span "test.nil" {} nil)))))

(deftest incr!-is-a-safe-no-op
  (is (nil? (telemetry/incr! "test.counter")))
  (is (nil? (telemetry/incr! "test.counter" {:reason "x"})))
  (testing "the three-argument arity is the one that takes an amount"
    (is (nil? (telemetry/incr! "test.counter" 5 {:reason "x"})))))

(deftest attributes-stringify-values
  (let [attrs (telemetry/attributes {:account_type "subscriber" :count 3})]
    (is (= "subscriber" (.get attrs (AttributeKey/stringKey "account_type"))))
    (is (= "3" (.get attrs (AttributeKey/stringKey "count")))))
  (testing "empty and nil both build an empty attribute set"
    (is (= 0 (.size (telemetry/attributes {}))))
    (is (= 0 (.size (telemetry/attributes nil))))))
