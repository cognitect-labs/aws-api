;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ssm-examples 
  (:require [clojure.spec.alpha :as s]
            [clojure.pprint :as pp]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.http.java-net :as java-http-client]))

(comment

  (def ssm-client (aws/client {:api :ssm}))

  ; or make a client with java-http-client wrapper
  (def s3 (aws/client {:api         :s3
                       :http-client (java-http-client/create)}))

  ;; guard against invalid :request map
  (aws/validate-requests ssm-client true)

  ;; what ops are available
  (aws/ops ssm-client)

  (-> (aws/ops ssm-client) keys sort)

  ;; print docs
  (aws/doc ssm-client :PutParameter)

  ;; or describe args as data
  (pp/pprint (s/describe (aws/request-spec-key ssm-client :PutParameter)))

  ;; describe an op param
  (s/form :cognitect.aws.ssm.PutParameterRequest/Type)

  ;; jam!
  (aws/invoke ssm-client {:op      :PutParameter
                          :request {:Name  "aws-api-example"
                                    :Value "example-value"
                                    :Type  "SecureString"}})

  ;; see spec fail
  (aws/invoke ssm-client {:op      :PutParameter
                          :request {:Value "example-value"
                                    :Type  "SecureString"}})

  )
