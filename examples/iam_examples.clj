;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns iam-examples
  (:require [cognitect.aws.client.api :as aws]
            [cognitect.aws.http.java-net :as java-http-client]))

(comment

  ;; make a client
  (def iam (aws/client {:api :iam}))

  ; or make a client with java-http-client wrapper
  (def s3 (aws/client {:api         :s3
                       :http-client (java-http-client/create)}))

  (aws/ops iam)

  (-> (aws/ops iam) keys sort)

  (aws/doc iam :ListRoles)

  (aws/invoke iam {:op :ListRoles})

  )
