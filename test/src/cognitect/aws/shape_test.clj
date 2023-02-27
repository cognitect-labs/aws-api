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

(deftest parse-json-structure
  (testing "no shape"
    (is (= {}
           (shape/json-parse* {:type "structure"}
                              [{:this "is" :a "doc"}]))))
  (testing "ignores unspecified members"
    (is (= {:a "b"}
           (shape/json-parse* (with-meta {:type "structure" :members {:a {:type "string"}}}
                                {:shapes {}})
                              {:a "b" :extra "whatever"}))))
  (testing ":document true"
    (is (= [{:this "is" :a "doc"}]
           (shape/json-parse* {:type "structure" :document true}
                              [{:this "is" :a "doc"}])))))
