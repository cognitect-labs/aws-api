(ns cognitect.aws.shape-test
  (:require [clojure.test :refer [deftest testing is]]
            [cognitect.aws.shape :as shape]))

(deftest test-parse-date
  (testing "returns nil for nil"
    (is (nil? (shape/parse-date {} nil)))
    (is (nil? (shape/parse-date {:timestampFormat "rfc822"} nil)))
    (is (nil? (shape/parse-date {:timestampFormat "iso8601"} nil))))
  (testing "returns nil for incorrect format"
    (is (nil? (shape/parse-date {:timestampFormat "rfc822"} "wrong")))
    (is (nil? (shape/parse-date {:timestampFormat "iso8601"} "wrong"))))
  (testing "iso8601 format handles presence and absence of fractional seconds"
    (is (= #inst "2020-07-06T10:59:13.000-00:00"
           (shape/parse-date {:timestampFormat "iso8601"} "2020-07-06T10:59:13Z")))
    (is (= #inst "2020-07-06T10:59:13.417-00:00"
           (shape/parse-date {:timestampFormat "iso8601"} "2020-07-06T10:59:13.417Z")))))
