(ns cognitect.aws.http.java.integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cognitect.aws.client.api :as aws]
            [clojure.java.io :as io])
  (:import (java.nio ByteBuffer)
           (java.time Instant)))

(defn ensure-test-profile
  [f]
  (if (= "aws-api-test" (System/getenv "AWS_PROFILE"))
    (f)
    (println "AWS_PROFILE is not configured, so not running integration tests. See README.")))

(use-fixtures :once ensure-test-profile)

(defn bucket-listed? [list-buckets-response bucket-name]
  (contains? (->> list-buckets-response
                  :Buckets
                  (map :Name)
                  (into #{}))
             bucket-name))

(deftest ^:integration test-s3-client
  (let [s3 (aws/client {:api :s3})
        bucket-name (str "aws-api-test-bucket-" (.getEpochSecond (Instant/now)))]
    
    (testing ":CreateBucket"
      (aws/invoke s3 {:op :CreateBucket :request {:Bucket bucket-name}})
      (is (bucket-listed? (aws/invoke s3 {:op :ListBuckets}) bucket-name)))

    (testing ":PutObject with byte-array"
      (aws/invoke s3 {:op :PutObject :request {:Bucket bucket-name
                                               :Key    "hello.txt"
                                               :Body   (.getBytes "Hello!")}})
      (is (= "Hello!" (->> (aws/invoke s3 {:op      :GetObject
                                           :request {:Bucket bucket-name
                                                     :Key    "hello.txt"}})
                           :Body
                           slurp))))

    (testing ":PutObject with input-stream"
      (aws/invoke s3 {:op :PutObject :request {:Bucket bucket-name
                                               :Key    "hai.txt"
                                               :Body   (io/input-stream (.getBytes "Oh hai!"))}})
      (is (= "Oh hai!" (->> (aws/invoke s3 {:op      :GetObject
                                            :request {:Bucket bucket-name
                                                      :Key    "hai.txt"}})
                            :Body
                            slurp))))

    (testing ":PutObject with ByteBuffer"
      (aws/invoke s3 {:op :PutObject :request {:Bucket bucket-name
                                               :Key    "oi.txt"
                                               :Body   (ByteBuffer/wrap (.getBytes "Oi!"))}})
      (is (= "Oi!" (->> (aws/invoke s3 {:op      :GetObject
                                            :request {:Bucket bucket-name
                                                      :Key    "oi.txt"}})
                            :Body
                            slurp))))
    
    (testing ":DeleteObjects and :DeleteBucket"
      (aws/invoke s3 {:op :DeleteObjects :request {:Bucket bucket-name
                                                   :Delete {:Objects [{:Key "hello.txt"}
                                                                      {:Key "hai.txt"}
                                                                      {:Key "oi.txt"}]}}})
      (aws/invoke s3 {:op :DeleteBucket :request {:Bucket bucket-name}})
      (is (not (bucket-listed? (aws/invoke s3 {:op :ListBuckets}) bucket-name))))))
