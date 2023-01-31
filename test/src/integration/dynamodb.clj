(ns integration.dynamodb
  (:require [clojure.test :refer :all]
            [integration.aux :as aux]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.http.java-net :as java-http-client])
  (:import (java.time Instant)))

(defn table-exists?
  [ddb table-name]
  (contains?
    (aws/invoke ddb {:op :DescribeTable :request {:TableName table-name}})
    :Table))

(use-fixtures :each aux/ensure-test-profile)

(deftest ^:integration ddb-test
  (let [ddb (aws/client {:api :dynamodb})
        table-name (str "aws-api-test-table-" (.getEpochSecond (Instant/now)))]
    (testing ":CreateTable"
      (aws/invoke ddb {:op      :CreateTable
                       :request {:TableName            table-name
                                 :AttributeDefinitions  [{:AttributeName "Name"
                                                          :AttributeType "S"}]
                                 :KeySchema             [{:AttributeName "Name"
                                                          :KeyType       "HASH"}]
                                 :ProvisionedThroughput {:ReadCapacityUnits  1
                                                         :WriteCapacityUnits 1}}})
      (is (table-exists? ddb table-name)))))

(deftest ^:integration ddb-test-with-java-net
  (let [ddb (aws/client {:api :dynamodb
                         :http-client (java-http-client/create)})
        table-name (str "aws-api-test-table-" (.getEpochSecond (Instant/now)))]
    (testing ":CreateTable"
      (aws/invoke ddb {:op      :CreateTable
                       :request {:TableName            table-name
                                 :AttributeDefinitions  [{:AttributeName "Name"
                                                          :AttributeType "S"}]
                                 :KeySchema             [{:AttributeName "Name"
                                                          :KeyType       "HASH"}]
                                 :ProvisionedThroughput {:ReadCapacityUnits  1
                                                         :WriteCapacityUnits 1}}})
      (is (table-exists? ddb table-name)))))
