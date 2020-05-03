(ns cognitect.aws.api-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [cognitect.aws.client :as client]
            [cognitect.aws.http :as http]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.flow.steps :as steps]
            [cognitect.aws.client.shared :as shared]))

(deftest test-underlying-http-client
  (testing "defaults to shared client"
    (let [contexts (repeatedly 5 #(let [c (aws/client {:api :s3 :region "us-east-1"})]
                                    (aws/invoke c {} [steps/add-http-provider-step])))]
      (is (= #{(shared/http-client)}
             (into #{(shared/http-client)}
                   (->> contexts (map :http-client))))))))

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
