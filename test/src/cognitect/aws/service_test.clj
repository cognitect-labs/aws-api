(ns cognitect.aws.service-test
  (:require [clojure.test :refer [deftest is testing]]
            [cognitect.aws.service :as service]))

(deftest service-protocol-test
  (testing "single supported protocol"
    (is (= "rest-json"
           (service/service-protocol {:metadata {:protocol  "rest-json"
                                                 :protocols ["rest-json"]}}))))

  (testing "multiple supported protocols, return preferred one"
    (is (= "json"
           (service/service-protocol {:metadata {:protocol  "json"
                                                 :protocols ["json" "query"]}}))))

  (testing "multiple protocols, preferred unsupported, return first supported"
    (is (= "json"
           (service/service-protocol {:metadata {:protocol  "smithy-rpc-v2-cbor"
                                                 :protocols ["smithy-rpc-v2-cbor" "json" "query"]}}))))

  (testing "missing legacy :protocol key is allowed"
    (is (= "json"
           (service/service-protocol {:metadata {:protocols ["json" "query"]}}))))

  (testing "missing new :protocols key is allowed"
    (is (= "rest-xml"
           (service/service-protocol {:metadata {:protocol "rest-xml"}}))))

  (testing "throws if no protocol is supported"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No supported protocol for service sample-service"
                          (service/service-protocol {:metadata {:uid      "sample-service-2026-01-01"
                                                                :protocol "smithy-rpc-v2-cbor"}})))

    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No supported protocol for service sample-service"
                          (service/service-protocol {:metadata {:uid      "sample-service-2026-01-01"
                                                                :protocols ["smithy-rpc-v2-cbor" "other-unsupported"]}})))))
