;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ec2-examples
  (:require [cognitect.aws.client.api :as aws]
            [cognitect.aws.http.java-net :as java-http-client]))

(comment

  (def ec2 (aws/client {:api :ec2}))

  ; or make a client with java-http-client wrapper
  (def s3 (aws/client {:api         :s3
                       :http-client (java-http-client/create)}))

  (aws/ops ec2)

  (-> (aws/ops ec2) keys sort)

  (aws/doc ec2 :DescribeAvailabilityZones)

  (aws/invoke ec2 {:op :DescribeAvailabilityZones})

  )
