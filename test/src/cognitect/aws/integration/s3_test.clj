(ns cognitect.aws.integration.s3-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as creds]
            [cognitect.aws.http.cognitect :as http-cognitect-client]
            [cognitect.aws.integration.fixtures :as fixtures]
            [cognitect.aws.test.utils :as utils])
  (:import (java.nio ByteBuffer)
           (java.time Instant)))

(use-fixtures :once fixtures/ensure-test-profile)

(defn invoke
  "Like cognitect.aws.client.api/invoke, but throws if not successful"
  [client op-map]
  (let [result (aws/invoke client op-map)]
    (if (or (contains? result :cognitect.anomalies/category)
            (contains? result :Error))
      (throw (ex-info "Request didn't complete with success"
                      {:result result}))
      result)))

(defn bucket-listed? [list-buckets-response bucket-name]
  (contains? (->> list-buckets-response
                  :Buckets
                  (map :Name)
                  (into #{}))
             bucket-name))

(defn test-s3-client
  [s3]
  (let [bucket-name (str "aws-api-test-bucket-" (.toEpochMilli (Instant/now)) "-" (rand-int 1000))]

    (testing ":CreateBucket"
      (invoke s3 {:op :CreateBucket :request {:Bucket bucket-name}})
      (is (bucket-listed? (invoke s3 {:op :ListBuckets}) bucket-name)))

    (testing ":PutObject with byte-array"
      (invoke s3 {:op :PutObject :request {:Bucket bucket-name
                                           :Key    "hello.txt"
                                           :Body   (.getBytes "Hello!")}})
      (is (= "Hello!" (->> (invoke s3 {:op      :GetObject
                                       :request {:Bucket bucket-name
                                                 :Key    "hello.txt"}})
                           :Body
                           slurp))))

    (testing ":PutObject with input-stream"
      (invoke s3 {:op :PutObject :request {:Bucket bucket-name
                                           :Key    "hai.txt"
                                           :Body   (io/input-stream (.getBytes "Oh hai!"))}})
      (is (= "Oh hai!" (->> (invoke s3 {:op      :GetObject
                                        :request {:Bucket bucket-name
                                                  :Key    "hai.txt"}})
                            :Body
                            slurp))))

    (testing ":PutObject with ByteBuffer"
      (invoke s3 {:op :PutObject :request {:Bucket bucket-name
                                           :Key    "oi.txt"
                                           :Body   (ByteBuffer/wrap (.getBytes "Oi!"))}})
      (is (= "Oi!" (->> (invoke s3 {:op      :GetObject
                                    :request {:Bucket bucket-name
                                              :Key    "oi.txt"}})
                        :Body
                        slurp))))

    (testing ":DeleteObjects and :DeleteBucket"
      (invoke s3 {:op :DeleteObjects :request {:Bucket bucket-name
                                               :Delete {:Objects [{:Key "hello.txt"}
                                                                  {:Key "hai.txt"}
                                                                  {:Key "oi.txt"}]}}})
      (invoke s3 {:op :DeleteBucket :request {:Bucket bucket-name}})
      (is (not (bucket-listed? (invoke s3 {:op :ListBuckets}) bucket-name))))))

(defn test-s3-client-with-http-client
  [http-client]
  (let [s3 (aws/client {:api :s3
                        :http-client http-client
                        :credentials-provider (creds/default-credentials-provider http-client)})]
    (test-s3-client s3)))

(deftest ^:integration test-default-http-client
  (testing "Default http client"
    (test-s3-client-with-http-client (aws/default-http-client))))

(deftest ^:integration test-cognitect-http-client
  (test-s3-client-with-http-client (http-cognitect-client/create)))

(utils/when-java11
 (require '[cognitect.aws.http.java :as http-java-client])
 (deftest ^:integration test-java-http-client
   (test-s3-client-with-http-client (http-java-client/create))))

(defn- for-each-http-client
  [f]
  (let [clients (remove nil? [(http-cognitect-client/create)
                              (utils/when-java11
                                (require '[cognitect.aws.http.java :as http-java-client])
                                (http-java-client/create))])]
    (doseq [client clients]
      (f client))))

(deftest ^:integration test-custom-endpoint-local-http
  ; Regression test for https://github.com/cognitect-labs/aws-api/issues/263
  ; Requires a local instance of MinIO:
  ;     docker run --rm -p 9000:9000 bitnami/minio
  (for-each-http-client
   #(let [s3 (aws/client {:api                  :s3
                          :http-client          %
                          :endpoint-override    {:protocol :http
                                                 :hostname "localhost"
                                                 :port     9000}
                          :credentials-provider (creds/basic-credentials-provider
                                                 {:access-key-id     "minio"
                                                  :secret-access-key "miniosecret"})})]
      (testing (str "with http client " (class %))
        (test-s3-client s3)))))

(deftest ^:integration test-custom-endpoint-remote-https
  ; Public MinIO playground
  ; https://min.io/docs/minio/linux/administration/minio-console.html#minio-console-play-login
  (for-each-http-client
   #(let [s3 (aws/client {:api                  :s3
                          :http-client          %
                          ; uses deprecated endpoint-override mode, that does not provide protocol and port
                          :endpoint-override    "play.min.io"
                          :credentials-provider (creds/basic-credentials-provider
                                                 {:access-key-id     "Q3AM3UQ867SPQQA43P2F"
                                                  :secret-access-key "zuf+tfteSlswRu7BJ86wekitnifILbZam1KYY3TG"})})]
      (testing (str "with http client " (class %))
        (test-s3-client s3)))))
