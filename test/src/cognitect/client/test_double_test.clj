(ns cognitect.client.test-double-test
  "Tests for the test double client implementation."
  (:require [clojure.test :refer [deftest is testing]]
            [cognitect.client.impl-test :as client-test]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.client.test-double :as client.test]))

(deftest keyword-access
  (let [c (client.test/client {:api :s3})]
    (is (= "s3" (:api c)))
    (is (map? (:service c)))))

(deftest register-handlers-on-creation
  (testing "supports handler functions (sync and async)"
    (let [user-provided-response {:Location "abc"}
          test-s3 (client.test/client {:api :s3 :ops {:CreateBucket
                                                      (constantly user-provided-response)}})]
      (doseq [invocation-response (client-test/invocations test-s3
                                                           {:op :CreateBucket
                                                            :request {:Bucket "bucket"}})]
        (is (= user-provided-response invocation-response))))))

(deftest register-handlers-with-instrument
  (let [user-provided-response {:Location "abc"}
        test-s3 (client.test/client {:api :s3})]
    (testing "register handler"
      (client.test/instrument test-s3 {:CreateBucket (constantly user-provided-response)})
      (doseq [invocation-response (client-test/invocations test-s3
                                                           {:op :CreateBucket
                                                            :request {:Bucket "bucket"}})]
        (is (= user-provided-response invocation-response))))))

(deftest raw-response-values
  (let [test-s3 (client.test/client {:api :s3})]
    (let [response {:Buckets []}]
      (client.test/instrument test-s3 {:ListBuckets response})
      (is (= response (aws/invoke test-s3 {:op :ListBuckets :request {}}))))
    (let [response {:Location "abc"}]
      (client.test/instrument test-s3 {:CreateBucket response})
      (is (= response (aws/invoke test-s3 {:op :CreateBucket :request {:Bucket "bucket"}}))))
    (let [response {:cognitect.anomalies/category :cognitect.anomalies/busy}]
      (client.test/instrument test-s3 {:CreateBucket response})
      (is (= response (aws/invoke test-s3 {:op :CreateBucket :request {:Bucket "bucket"}}))))))

(deftest custom-testing-strategies
  (let [calls   (atom [])
        test-s3 (client.test/client {:api :s3
                                     :ops {:CreateBucket
                                           (fn [op-map]
                                             (swap! calls conj op-map)
                                             {:Location "over the rainbow"})}})]
    (dotimes [n 5]
      (aws/invoke test-s3 {:op :CreateBucket
                           :request {:Bucket (str "bucket-" n)}})
      (is (= (take (inc n) [{:op :CreateBucket, :request {:Bucket "bucket-0"}}
                            {:op :CreateBucket, :request {:Bucket "bucket-1"}}
                            {:op :CreateBucket, :request {:Bucket "bucket-2"}}
                            {:op :CreateBucket, :request {:Bucket "bucket-3"}}
                            {:op :CreateBucket, :request {:Bucket "bucket-4"}}])
             @calls)))))

(deftest test-anomalies-and-exceptions
  (testing "returns an anomaly with spec report when invoked with invalid request"
    (let [res (aws/invoke
               (client.test/client {:api :s3})
               {:op :CreateBucket
                :request {:BucketName "key should be :Bucket"}})]
      (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category res)))
      (is (:clojure.spec.alpha/problems res))))

  (testing "returns an anomaly when invoking op unsupported by service"
    (let [res (aws/invoke
               (client.test/client {:api :s3 :ops {}})
               {:op :DoesNotExistInS3})]
      (is (= :cognitect.anomalies/unsupported (:cognitect.anomalies/category res)))
      (is (= "Operation not supported" (:cognitect.anomalies/message res)))))

  (testing "returns an anomaly when invoking undeclared operation"
    (let [res (aws/invoke
               (client.test/client {:api :s3 :ops {}})
               {:op      :CreateBucket
                :request {:Bucket "bucket"}})]
      (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category res)))
      (is (= "No handler or response provided for op" (:cognitect.anomalies/message res)))))

  (testing "throws when instrumenting op unsupported by service"
    (is (thrown-with-msg? RuntimeException
                          #"Operation not supported"
                          (client.test/client {:api :s3 :ops {:DoesNotExistInS3 {}}})))
    (let [client (client.test/client {:api :s3})]
      (is (thrown-with-msg? RuntimeException
                            #"Operation not supported"
                            (client.test/instrument client {:DoesNotExistInS3 {}}))))))
