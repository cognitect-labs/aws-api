;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(require '[cognitect.aws.client.api :as aws])

;; make a client
(def client (aws/client {:api :lambda}))

(aws/ops client)

(aws/doc client :ListFunctions)

(aws/invoke client {:op :ListFunctions})
