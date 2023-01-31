(ns integration.s3
  (:require [clojure.test :refer :all]
            [cognitect.aws.client.api :as aws]
            [clojure.java.io :as io]
            [integration.aux :as aux])
  (:import (java.time Instant)))

(defn bucket-exists? [s3 bucket-name]
  (contains? (->> (aws/invoke s3 {:op :ListBuckets})
                :Buckets
                (map :Name)
                (into #{}))
             bucket-name))

(defn object-exists? [s3 bucket-name key]
  (-> (aws/invoke s3 {:op :GetObject :request {:Bucket bucket-name :Key key}})
      (contains? :Error)
      not))

(defn test-delete-object
  [s3 bucket-name key]
  (testing key
    (is (object-exists? s3 bucket-name key))

     (aws/invoke s3 {:op :DeleteObjects :request {:Bucket bucket-name
                                                  :Delete {:Objects [{:Key key}]}}})
     (is (not (object-exists? s3 bucket-name key)))))

(use-fixtures :each aux/ensure-test-profile)

(deftest ^:integration test-s3
   (let [s3 (aws/client {:api         :s3})
         bucket-name (str "aws-api-test-bucket-" (.getEpochSecond (Instant/now)))]
     (testing ":CreateBucket"
        (aws/invoke s3 {:op :CreateBucket :request {:Bucket bucket-name}})

        (is (bucket-exists? s3 bucket-name)))

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

     (test-delete-object s3 bucket-name "hello.txt")
     (test-delete-object s3 bucket-name "hai.txt")

     (testing ":DeleteBucket"
        (aws/invoke s3 {:op :DeleteBucket :request {:Bucket bucket-name}})

        (is (not (bucket-exists? s3 bucket-name))))))
