;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(require '[cognitect.aws.client.api :as aws])

(def client (aws/client {:api :lambda}))

(aws/ops client)

(-> (aws/ops client) keys sort)

(aws/doc client :ListFunctions)

(aws/invoke client {:op :ListFunctions})
