;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

;; This example is taken from the AWS DynamoDB developer guide, with
;; additions to explore the capabilities of cognitect.aws.
;; See: http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/SampleData.html

(ns dynamodb-examples
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [cognitect.aws.client.api :as aws]))

(comment

  ;; 0 Create a client to talk to DynamoDB
  (def ddb (aws/client {:api :dynamodb}))

  ;; ask what it can do
  (aws/ops ddb)

  ;; doc!
  (aws/doc ddb :ListTables)
  (aws/doc ddb :CreateTable)
  (aws/doc ddb :Scan) ;; this one has a Given section with shape/AttributeValue

  ;; 1. Create Example Tables

  (aws/invoke ddb {:op :ListTables})

  (aws/invoke ddb
              {:op      :CreateTable
               :request {:TableName             "Forum"
                         :AttributeDefinitions  [{:AttributeName "Name"
                                                  :AttributeType "S"}]
                         :KeySchema             [{:AttributeName "Name"
                                                  :KeyType       "HASH"}]
                         :ProvisionedThroughput {:ReadCapacityUnits  1
                                                 :WriteCapacityUnits 1}}})

  (aws/invoke ddb {:op :ListTables})

  (aws/invoke ddb {:op :DescribeTable
                   :request {:TableName "Forum"}})

  (aws/invoke ddb
              {:op      :CreateTable
               :request {:TableName             "Thread"
                         :AttributeDefinitions  [{:AttributeName "ForumName"
                                                  :AttributeType "S"}
                                                 {:AttributeName "Subject"
                                                  :AttributeType "S"}]
                         :KeySchema             [{:AttributeName "ForumName"
                                                  :KeyType       "HASH"}
                                                 {:AttributeName "Subject"
                                                  :KeyType       "RANGE"}]
                         :ProvisionedThroughput {:ReadCapacityUnits  1
                                                 :WriteCapacityUnits 1}}})

  (aws/invoke ddb
              {:op      :CreateTable
               :request {:TableName              "Reply"
                         :AttributeDefinitions   [{:AttributeName "Id"
                                                   :AttributeType "S"}
                                                  {:AttributeName "ReplyDateTime"
                                                   :AttributeType "S"}
                                                  {:AttributeName "PostedBy"
                                                   :AttributeType "S"}
                                                  {:AttributeName "Message"
                                                   :AttributeType "S"}]
                         :KeySchema              [{:AttributeName "Id"
                                                   :KeyType       "HASH"}
                                                  {:AttributeName "ReplyDateTime"
                                                   :KeyType       "RANGE"}]
                         :GlobalSecondaryIndexes [{:IndexName             "PostedBy-Message-Index"
                                                   :KeySchema             [{:AttributeName "PostedBy"
                                                                            :KeyType       "HASH"}
                                                                           {:AttributeName "Message"
                                                                            :KeyType       "RANGE"}]
                                                   :Projection            {:ProjectionType "ALL"}
                                                   :ProvisionedThroughput {:ReadCapacityUnits  1
                                                                           :WriteCapacityUnits 1}}]
                         :ProvisionedThroughput  {:ReadCapacityUnits  1
                                                  :WriteCapacityUnits 1}}})

  (->> ["Forum" "Reply" "Thread"]
       (map #(aws/invoke ddb {:op      :DescribeTable
                              :request {:TableName %}
                              :ch      (a/promise-chan (comp
                                                        (map :Table)
                                                        (map :TableStatus)))}))
       (into #{}))

  ;; when ^^ returns #{"ACTIVE"}, the tables are all ready

  ;; 2. Load the Data

  (aws/doc ddb :BatchWriteItem)

  (let [ ;; The aws-supplied example data are all json. We can use them
        ;; as/is, but we need keys defined the input specs to be
        ;; keywords if we want to validate the structure first!
        xform-specified-keys
        (fn [k]
          (get (reduce (fn [a v] (assoc a v (keyword v)))
                       {}
                       ["B" "BOOL" "BS" "Item" "L" "M" "N" "NS" "NULL" "PutRequest" "S" "SS"])
               k
               k))]
    (->> ["Forum.json"
          "Reply.json"
          "Thread.json"]
         (map #(-> (io/file "examples" "resources" "dynamodb" %)
                   slurp
                   (json/read-str :key-fn xform-specified-keys)))
         (map #(aws/invoke ddb {:op      :BatchWriteItem
                                :request {:RequestItems %}}))))

  ;; 3. Query the Data

  (aws/invoke ddb
              {:op      :Query
               :request {:TableName                 "Reply"
                         :KeyConditionExpression    "Id = :id"
                         :ExpressionAttributeValues {":id" {:S "Amazon DynamoDB#DynamoDB Thread 1"}}}})

  (aws/invoke ddb
              {:op      :Query
               :request {:TableName                 "Reply"
                         :IndexName                 "PostedBy-Message-Index"
                         :KeyConditionExpression    "PostedBy = :user"
                         :ExpressionAttributeValues {":user" {:S "User A"}}}})

  ;; 4. Delete the tables

  (->> ["Forum" "Reply" "Thread"]
       (map #(aws/invoke ddb {:op      :DeleteTable
                              :request {:TableName %}}))
       (into []))

  )
