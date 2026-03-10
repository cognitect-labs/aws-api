(ns cognitect.aws.integration.error-codes-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.integration.fixtures :as fixtures]))

(use-fixtures :once fixtures/ensure-test-profile)

(deftest ^:integration error-codes-for-protocols
  (testing "rest-xml"
    (is (= "NoSuchBucket"
           (:cognitect.aws.error/code
            (aws/invoke (aws/client {:api :s3})
                        {:op      :GetObject
                         :request {:Bucket "i-do-not-exist"
                                   :Key    "neither-do-i.txt"}})))))

  (testing "rest-json"
    (is (= "ValidationException"
           (:cognitect.aws.error/code
            (aws/invoke (aws/client {:api :lambda})
                        {:op :CreateFunction})))))

  (testing "ec2"
    (is (= "MissingParameter"
           (:cognitect.aws.error/code
            (aws/invoke (aws/client {:api :ec2})
                        {:op :CreateVolume})))))

  (testing "query"
    (is (= "ValidationError"
           (:cognitect.aws.error/code
            (aws/invoke (aws/client {:api :autoscaling})
                        {:op :DescribeTrafficSources})))))

  (testing "json"
    (is (= "ValidationException"
           (:cognitect.aws.error/code
            (aws/invoke (aws/client {:api :ssm})
                        {:op :DescribePatchGroupState}))))))
