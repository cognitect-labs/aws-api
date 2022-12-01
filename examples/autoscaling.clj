(ns autoscaling
  (:require [cognitect.aws.client.api :as aws]))

(comment

  (def client (aws/client {:api :autoscaling}))
  (aws/invoke client {:op :DescribeAutoScalingGroups
                      :request {}})

  )
