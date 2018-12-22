;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(require '[cognitect.aws.client.api :as aws])

(def ec2 (aws/client {:api :ec2}))

(aws/ops ec2)

(-> (aws/ops ec2) keys sort)

(aws/doc ec2 :DescribeAvailabilityZones)

(aws/invoke ec2 {:op :DescribeAvailabilityZones})
