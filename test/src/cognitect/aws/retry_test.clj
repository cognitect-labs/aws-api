;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.retry-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as a]
            [cognitect.aws.retry :as retry]))

(deftest test-no-retry
  (is (= {:this :map}
         (let [c (a/chan 1)
               _ (a/>!! c {:this :map})
               response-ch (retry/with-retry
                             (constantly c)
                             (a/promise-chan)
                             (constantly false)
                             (constantly nil))]
           (a/<!! response-ch)))))

(deftest test-with-default-retry
  (testing "nil response from backoff"
    (is (= {:this :map}
           (let [c (a/chan 1)
                 _ (a/>!! c {:this :map})
                 response-ch (retry/with-retry
                               (constantly c)
                               (a/promise-chan)
                               (constantly true)
                               (constantly nil))]
             (a/<!! response-ch)))))
  (testing "always busy"
    (let [max-retries 2]
      (is (= {:cognitect.anomalies/category :cognitect.anomalies/busy :test/attempt-number 3}
             (let [c (a/chan 3)]
               (a/>!! c {:cognitect.anomalies/category :cognitect.anomalies/busy
                         :test/attempt-number 1})
               (a/>!! c {:cognitect.anomalies/category :cognitect.anomalies/busy
                         :test/attempt-number 2})
               (a/>!! c {:cognitect.anomalies/category :cognitect.anomalies/busy
                         :test/attempt-number 3})
               (a/<!! (retry/with-retry
                        (constantly c)
                        (a/promise-chan)
                        retry/default-retriable?
                        (retry/capped-exponential-backoff 50 500 max-retries))))))))
  (testing "always unavailable"
    (let [max-retries 2]
      (is (= {:cognitect.anomalies/category :cognitect.anomalies/unavailable :test/attempt-number 3}
             (let [c (a/chan 3)]
               (a/>!! c {:cognitect.anomalies/category :cognitect.anomalies/unavailable
                         :test/attempt-number 1})
               (a/>!! c {:cognitect.anomalies/category :cognitect.anomalies/unavailable
                         :test/attempt-number 2})
               (a/>!! c {:cognitect.anomalies/category :cognitect.anomalies/unavailable
                         :test/attempt-number 3})
               (a/<!! (retry/with-retry
                        (constantly c)
                        (a/promise-chan)
                        retry/default-retriable?
                        (retry/capped-exponential-backoff 50 500 max-retries))))))))
  (testing "3rd time is the charm"
    (let [max-retries 3]
      (is (= {:test/attempt-number 3}
             (let [c (a/chan 3)]
               (a/>!! c {:cognitect.anomalies/category :cognitect.anomalies/busy
                         :test/attempt-number 1})
               (a/>!! c {:cognitect.anomalies/category :cognitect.anomalies/busy
                         :test/attempt-number 2})
               (a/>!! c {:test/attempt-number 3})
               (a/<!! (retry/with-retry
                        (constantly c)
                        (a/promise-chan)
                        retry/default-retriable?
                        (retry/capped-exponential-backoff 50 500 max-retries)))))))))