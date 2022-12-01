;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ec2-examples
  (:require [cognitect.aws.client.api :as aws]))

(comment

  (def ec2 (aws/client {:api :ec2}))

  (aws/ops ec2)

  (-> (aws/ops ec2) keys sort)

  (aws/doc ec2 :DescribeAvailabilityZones)

  (aws/invoke ec2 {:op :DescribeAvailabilityZones})

  )
