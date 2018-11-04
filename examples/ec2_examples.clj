;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(require '[cognitect.aws.client.api :as aws])

;; make a client
(def client (aws/client {:api :ec2}))

(aws/ops client)

(aws/doc client :DescribeAvailabilityZones)

(aws/invoke client {:op :DescribeAvailabilityZones})
