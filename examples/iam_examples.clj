;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns iam-examples
  (:require [cognitect.aws.client.api :as aws]))

(comment

  ;; make a client
  (def iam (aws/client {:api :iam}))

  (aws/ops iam)

  (-> (aws/ops iam) keys sort)

  (aws/doc iam :ListRoles)

  (aws/invoke iam {:op :ListRoles})

  )
