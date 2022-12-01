(ns cognitect.aws.api-test
  (:require [clojure.datafy :as datafy]
            [clojure.test :as t :refer [deftest is testing]]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.client.protocol :as client.protocol]
            [cognitect.aws.client.shared :as shared]
            [cognitect.aws.http :as http]))

(deftest test-underlying-http-client
  (testing "defaults to shared client"
    (let [clients (repeatedly 5 #(aws/client {:api :s3 :region "us-east-1"}))]
      (is (= #{(shared/http-client)}
             (into #{(shared/http-client)}
                   (->> clients (map (fn [c] (-> c client.protocol/-get-info :http-client))))))))))

(deftest test-datafy
  (let [client (aws/client {:api :s3})
        data (datafy/datafy client)]
    (is (= "s3" (:api data)))
    (is (map? (:service data)))
    (is (map? (:metadata (:service data))))
    (is (map? (:endpoint data)))
    (is (string? (:region data)))
    (is (map? (:ops data)))
    (is (map? (:endpoint data)))))

(deftest test-stop
  (let [call-count (atom 0)
        default           (aws/client {:api :s3 :region "us-east-1"})
        supplied-shared   (aws/client {:api         :s3 :region "us-east-1"
                                       :http-client (shared/http-client)})
        supplied-unshared (aws/client {:api         :s3 :region "us-east-1"
                                       :http-client (http/resolve-http-client nil)})]
    (with-redefs [http/stop (fn [_] (swap! call-count inc))]
      (testing "has no effect when aws-client uses the default shared http-client"
        (aws/stop default)
        (is (zero? @call-count)))
      (testing "has no effect when user supplies the shared http-client"
        (aws/stop supplied-shared)
        (is (zero? @call-count)))
      (testing "forwards to http/stop when user supplies a different http-client"
        (aws/stop supplied-unshared)
        (is (= 1 @call-count))))
    ;; now actually stop the aws-client with the non-shared http-client
    (aws/stop supplied-unshared)))
