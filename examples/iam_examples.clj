;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(require '[cognitect.aws.client.api :as aws])

;; make a client
(def client (aws/client {:api :iam}))

(aws/ops client)

(-> (aws/ops client) keys sort)

(aws/doc client :ListRoles)

(aws/invoke client {:op :ListRoles})
