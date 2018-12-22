;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(require '[cognitect.aws.client.api :as aws])

(def lambda (aws/client {:api :lambda}))

(aws/ops lambda)

(-> (aws/ops lambda) keys sort)

(aws/doc lambda :ListFunctions)

(aws/invoke lambda {:op :ListFunctions})
