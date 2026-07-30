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
           (shape/json-parse* {}
                              {:type "structure"}
                              [{:this "is" :a "doc"}]))))
  (testing "ignores unspecified members"
    (is (= {:a "b"}
           (shape/json-parse* {}
                              {:type "structure" :members {:a {:type "string"}}}
                              {:a "b" :extra "whatever"}))))
  (testing ":document true"
    (is (= [{:this "is" :a "doc"}]
           (shape/json-parse* {}
                              {:type "structure" :document true}
                              [{:this "is" :a "doc"}])))))

(deftest test-json-serialize
  (is (= "{\"this\":\"is\",\"a\":\"doc\"}"
         (shape/json-serialize {}
                               {:type "structure", :members {}, :document true}
                               {:this "is" :a "doc"}))))

(deftest format-date-test
  (testing "java.util.Date values"
    (let [data #inst "2026-07-29T22:36:35.339-00:00"]
      (is (= "Wed, 29 Jul 2026 22:36:35 GMT"
             (shape/format-date {:timestampFormat "rfc822"} data)))
      (is (= "2026-07-29T22:36:35Z"
             (shape/format-date {:timestampFormat "iso8601"} data)))
      (is (= "1785364595"
             (shape/format-date {:timestampFormat "unixTimestamp"} data)))))

  (testing "java.time.Instant values"
    (let [data (java.time.Instant/parse "2026-07-29T22:36:35.339Z")]
      (is (= "Wed, 29 Jul 2026 22:36:35 GMT"
             (shape/format-date {:timestampFormat "rfc822"} data)))
      (is (= "2026-07-29T22:36:35Z"
             (shape/format-date {:timestampFormat "iso8601"} data)))
      (is (= "1785364595"
             (shape/format-date {:timestampFormat "unixTimestamp"} data)))))

  (testing "custom protocol extension"
    (extend-protocol Inst
      Long
      (inst-ms* [inst] inst))

    (let [data 1785364595339]
      (is (inst? data))
      (is (= "Wed, 29 Jul 2026 22:36:35 GMT"
             (shape/format-date {:timestampFormat "rfc822"} data)))
      (is (= "2026-07-29T22:36:35Z"
             (shape/format-date {:timestampFormat "iso8601"} data)))
      (is (= "1785364595"
             (shape/format-date {:timestampFormat "unixTimestamp"} data))))))
