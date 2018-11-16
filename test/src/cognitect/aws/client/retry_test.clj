(ns cognitect.aws.client.retry-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async]
            [cognitect.aws.client.retry :as retry]))

(deftest test-no-retry
  (is (= {:this :map}
         (let [c (async/chan 1)
               _ (async/>!! c {:this :map})
               response-ch (retry/with-retry
                             (constantly c)
                             (async/promise-chan)
                             (constantly false)
                             (constantly nil))]
           (async/<!! response-ch)))))

(deftest test-with-default-retry
  (testing "nil response from backoff"
           (is (= {:this :map}
                  (let [c (async/chan 1)
                        _ (async/>!! c {:this :map})
                        response-ch (retry/with-retry
                                      (constantly c)
                                      (async/promise-chan)
                                      (constantly true)
                                      (constantly nil))]
                    (async/<!! response-ch)))))
  (testing "always busy"
    (let [max-retries 2]
      (is (= {:cognitect.anomalies/category :cognitect.anomalies/busy :test/attempt-number 3}
             (let [c (async/chan 3)]
               (async/>!! c {:cognitect.anomalies/category :cognitect.anomalies/busy
                             :test/attempt-number 1})
               (async/>!! c {:cognitect.anomalies/category :cognitect.anomalies/busy
                             :test/attempt-number 2})
               (async/>!! c {:cognitect.anomalies/category :cognitect.anomalies/busy
                             :test/attempt-number 3})
               (async/<!! (retry/with-retry
                            (constantly c)
                            (async/promise-chan)
                            retry/default-retriable?
                            (retry/capped-exponential-backoff 50 500 max-retries))))))))
  (testing "always unavailable"
    (let [max-retries 2]
      (is (= {:cognitect.anomalies/category :cognitect.anomalies/unavailable :test/attempt-number 3}
             (let [c (async/chan 3)]
               (async/>!! c {:cognitect.anomalies/category :cognitect.anomalies/unavailable
                             :test/attempt-number 1})
               (async/>!! c {:cognitect.anomalies/category :cognitect.anomalies/unavailable
                             :test/attempt-number 2})
               (async/>!! c {:cognitect.anomalies/category :cognitect.anomalies/unavailable
                             :test/attempt-number 3})
               (async/<!! (retry/with-retry
                            (constantly c)
                            (async/promise-chan)
                            retry/default-retriable?
                            (retry/capped-exponential-backoff 50 500 max-retries))))))))
  (testing "3rd time is the charm"
    (let [max-retries 3]
      (is (= {:test/attempt-number 3}
             (let [c (async/chan 3)]
               (async/>!! c {:cognitect.anomalies/category :cognitect.anomalies/busy
                             :test/attempt-number 1})
               (async/>!! c {:cognitect.anomalies/category :cognitect.anomalies/busy
                             :test/attempt-number 2})
               (async/>!! c {:test/attempt-number 3})
               (async/<!! (retry/with-retry
                            (constantly c)
                            (async/promise-chan)
                            retry/default-retriable?
                            (retry/capped-exponential-backoff 50 500 max-retries)))))))))

(comment
  (run-tests)
  )
