(ns autoscaling
  (:require [cognitect.aws.client.api :as aws]
            [cognitect.aws.http.java-net :as java-http-client]))

(comment

  (def client (aws/client {:api :autoscaling}))

  ; or make a client with java-http-client wrapper
  (def s3 (aws/client {:api         :s3
                       :http-client (java-http-client/create)}))

  (aws/invoke client {:op :DescribeAutoScalingGroups
                      :request {}})

  )
