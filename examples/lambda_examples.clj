;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns lambda-examples 
  (:require [cognitect.aws.client.api :as aws]))

(comment

  (def lambda (aws/client {:api :lambda}))

  (aws/ops lambda)

  (-> (aws/ops lambda) keys sort)

  (aws/doc lambda :ListFunctions)

  (aws/invoke lambda {:op :ListFunctions})

  )
