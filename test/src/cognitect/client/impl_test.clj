(ns cognitect.client.impl-test
  "Tests for the production client implementation."
  (:require [clojure.core.async :as a]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.test :as t :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.client.impl :as client]
            [cognitect.aws.client.protocol :as client.protocol]
            [cognitect.aws.credentials :as creds]
            [cognitect.aws.http :as http]
            [cognitect.aws.region :as region]
            [cognitect.aws.util :as util])
  (:import (java.nio ByteBuffer)))

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

(defspec status-over-299-appears-in-response-body
  (prop/for-all [status (gen/choose 300 599)]
                (= status (:cognitect.aws.http/status (#'client/handle-http-response {} {} {:status status})))))

(defspec status-below-300-does-not-appear-in-response-body
  (prop/for-all [status (gen/choose 200 299)]
                (nil? (:cognitect.aws.http/status (#'client/handle-http-response {} {} {:status status})))))

(def list-buckets-http-response
  (xml/indent-str
   (xml/element :ListAllMyBucketsResult {}
                (xml/element :Buckets {}
                             (xml/element :Bucket {}
                                          (xml/element :CreationDate {} "2023-01-23T11:59:03.575496Z")
                                          (xml/element :Name {} "test-bucket")))
                (xml/element :Owner {}
                             (xml/element :DisplayName {} "cognitect-aws")
                             (xml/element :ID {} "a3a42310-42d0-46d1-9745-0cee9f4fb851")))))

(def list-buckets-aws-client-response
  {:Buckets
   [{:Name "test-bucket",
     :CreationDate #inst "2023-01-23T12:08:38.496-00:00"}],
   :Owner
   {:DisplayName "cognitect-aws",
    :ID "a3a42310-42d0-46d1-9745-0cee9f4fb851"}})

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

(deftest test-invoke-happy-path
  (let [;; taken from a real response to a real request
        headers {"x-amz-request-id" "99FB3VJ1V9DECG8R"
                 "x-amz-id-2" "su0gWxpn1f0Z4gdYQ7GJUeoYAgIF0lawNDI0NZY57Bv95H+/d3ZruWft3Qz3VB3zau6V/4TB7uo="
                 "date" "Mon, 23 Jan 2023 12:18:59 GMT"
                 "content-type" "application/xml"}
        http-client (stub-http-client {:status 200
                                       :headers headers
                                       :body (ByteBuffer/wrap (.getBytes list-buckets-http-response))})
        s3 (aws/client (assoc params :http-client http-client))]
    (doseq [res (invocations s3 {:op :ListBuckets})]
      (is (= list-buckets-aws-client-response res))
      (is (= 200 (-> res meta :http-response :status)))
      (is (= headers (-> res meta :http-response :headers))))))

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
          (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category res))))))))

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

(deftest read-input-stream-once
  (let [retries (atom 3)
        reqs (atom [])
        client (aws/client (assoc params
                                  :retriable? (fn [_]
                                                (swap! retries dec)
                                                (pos? @retries))
                                  :http-client (reify http/HttpClient
                                                 (-submit [_ req ch]
                                                          (swap! reqs conj req)
                                                          (a/go (a/>! ch :ok))
                                                          ch)
                                                 (-stop [_]))))]
    (aws/invoke client {:op :PutObject :request {:Bucket "test-bucket"
                                                 :Key "test-object"
                                                 :Body (io/input-stream (.getBytes "test"))}})
    (is (= #{"test"} (into #{} (map (comp util/bbuf->str :body)) @reqs)))))
