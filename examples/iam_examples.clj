;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(require '[cognitect.aws.client.api :as aws])

;; make a client
(def iam (aws/client {:api :iam}))

(aws/ops iam)

(-> (aws/ops iam) keys sort)

(aws/doc iam :ListRoles)

(aws/invoke iam {:op :ListRoles})
