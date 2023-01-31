(ns cognitect.client.impl-test
  "Tests for the production client implementation."
  (:require [clojure.core.async :as a]
            [clojure.test :as t :refer [deftest is testing]]
            [cognitect.aws.client.impl :as client]
            [cognitect.aws.client.protocol :as client.protocol]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as creds]
            [cognitect.aws.http :as http]
            [cognitect.aws.region :as region]))

(defn stub-http-client [result]
  (reify http/HttpClient
    (-submit [_ _ ch]
      (a/go (a/>! ch result))
      ch)
    (-stop [_])))

(defn stub-credentials-provider [creds]
  (reify creds/CredentialsProvider
    (fetch [_] creds)))

(defn stub-region-provider [region]
  (reify region/RegionProvider
    (fetch [_] region)))

(def params {:api                  :s3
             ;; use an anomaly to bypass parsing http-response
             :http-client          (stub-http-client {:cognitect.anomalies/category :cognitect.aws/test
                                                      :cognitect.anomalies/message  "test"})
             :region-provider      (stub-region-provider :us-east-1)
             :credentials-provider (stub-credentials-provider
                                    {:access-key-id     "a"
                                     :secret-access-key "b"})})

(deftest test-handle-http-response
  (testing "returns http-response if it is an anomaly"
    (is (= {:cognitect.anomalies/category :does-not-matter}
           (#'client/handle-http-response {} {} {:cognitect.anomalies/category :does-not-matter})))))

(defn invocations
  "Given client and op-map, return results of:
    invoke (sync)
    invoke-async with channel provided by user
    invoke-async with no channel provided by user"
  [client op-map]
  [(aws/invoke client op-map)
   (a/<!! (aws/invoke-async client op-map))
   (let [ch (a/chan)
         _  (aws/invoke-async client (assoc op-map :ch ch))]
     (a/<!! ch))])

(deftest test-invoke
  (let [s3 (aws/client params)]
    (testing "request meta"
      (doseq [res (invocations s3 {:op :ListBuckets})]
        (testing "includes raw response"
          (is (= {:cognitect.anomalies/category :cognitect.aws/test,
                  :cognitect.anomalies/message "test",
                  :body nil}
                 (:http-response (meta res)))))
        (testing "includes :http-request"
          (is (=  {:uri "/"
                   :server-name "s3.amazonaws.com"
                   :body nil}
                  (select-keys (:http-request (meta res)) [:uri :server-name :body]))))))
    (testing "returns :cognitect.anomalies/unsupported when op is not supported"
      (doseq [res (invocations s3 {:op      :CreateBuckets
                                   :request {}})]
        (is (= :cognitect.anomalies/unsupported (:cognitect.anomalies/category res)))))
    (testing "with validate-requests true"
      (testing "returns :cognitect.anomalies/incorrect when request is invalid"
        (aws/validate-requests s3 true)
        (doseq [res (invocations s3 {:op      :CreateBucket
                                     :request {:this :is :not :valid}})]
          (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category res))))))
    (testing "with validate-requests false"
      (testing "returns a different anomaly when request is invalid"
        (aws/validate-requests s3 false)
        (doseq [res (invocations s3 {:op      :CreateBucket
                                     :request {:this :is :not :valid}})]
          (is (= :cognitect.anomalies/fault (:cognitect.anomalies/category res))))))))

(deftest test-providers
  (testing "base case"
    (let [aws-client (aws/client params)]
      (is (= "test"
             (:cognitect.anomalies/message
              (aws/invoke aws-client {:op :ListBuckets}))))))
  (testing "nil creds (regression test - should not hang)"
    (let [aws-client (aws/client (assoc params
                                        :credentials-provider
                                        (stub-credentials-provider nil)))]
      (is (re-find #"^Unable to fetch credentials"
                   (:cognitect.anomalies/message
                    (aws/invoke aws-client {:op :ListBuckets}))))))
  (testing "empty creds (regression test - should not hang)"
    (let [aws-client (aws/client (assoc params
                                        :credentials-provider
                                        (stub-credentials-provider {})))]
      (is (= "test"
             (:cognitect.anomalies/message
              (aws/invoke aws-client {:op :ListBuckets}))))))
  (testing "nil region (regression test - should not hang)"
    (let [aws-client (aws/client (assoc params
                                        :region-provider
                                        (stub-region-provider nil)))]
      (is (re-find #"^Unable to fetch region"
                   (:cognitect.anomalies/message
                    (aws/invoke aws-client {:op :ListBuckets}))))))
  (testing "empty region (regression test - should not hang)"
    (let [aws-client (aws/client (assoc params
                                        :region-provider
                                        (stub-region-provider "")))]
      (is (re-find #"^No known endpoint."
                   (:cognitect.anomalies/message
                    (aws/invoke aws-client {:op :ListBuckets})))))))

(deftest keyword-access
  (let [client (aws/client params)]
    (is (= "s3" (:api client)))
    (is (= :us-east-1 (:region client)))
    (is (= "s3.amazonaws.com" (:hostname (:endpoint client))))
    (is (= {:access-key-id "a", :secret-access-key "b"}
           (:credentials client)))
    (is (= (:metadata (:service (client.protocol/-get-info client)))
           (:metadata (:service client))))
    (is (= (:http-client params)
           (:http-client client)))))
