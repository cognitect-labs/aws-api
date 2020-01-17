(ns cognitect.aws.client-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [cognitect.aws.client :as client]))

(deftest test-handle-http-response
  (testing "returns http-response if it is an anomaly"
    (is (= {:cognitect.anomalies/category :does-not-matter}
           (#'client/handle-http-response {} {} {:cognitect.anomalies/category :does-not-matter})))))
