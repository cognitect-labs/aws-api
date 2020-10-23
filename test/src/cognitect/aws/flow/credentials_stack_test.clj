(ns cognitect.aws.flow.credentials-stack-test
  (:require [clojure.test :refer [deftest testing is]]
            [matcher-combinators.test]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [cognitect.aws.flow.credentials-stack :as credentials-stack]))

(deftest user-provided-credentials-provider
  (testing "user-provided credentials provider (backward compatibility)"
    (testing "takes precedence"
      (let [c (aws/client {:credentials-provider
                           (credentials/basic-credentials-provider
                            {:access-key-id     "username"
                             :secret-access-key "password"})})]
        (is (match? {:aws/access-key-id     "username"
                     :aws/secret-access-key "password"}
                    (:credentials (aws/invoke c {:workflow-steps
                                                 [(credentials-stack/process-credentials)]}))))))
    (testing "anomaly when returns no credentials"
      (let [c (aws/client {:credentials-provider
                           (reify credentials/CredentialsProvider
                             (fetch [_]))})]
        (is (:cognitect.anomalies/category
             (aws/invoke c {:workflow-steps
                            [(credentials-stack/process-credentials)]})))))))

;; TODO (dchelimsky,2020-05-22) still need to test the default stack
