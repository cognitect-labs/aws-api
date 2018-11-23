;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(require '[cognitect.aws.client.api :as aws])

(def client (aws/client {:api :ec2}))

(aws/ops-data client)

(-> (aws/ops-data client) keys sort)

(aws/doc client :DescribeAvailabilityZones)

(aws/invoke client {:op :DescribeAvailabilityZones})
