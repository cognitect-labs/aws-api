;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.credentials-test
  (:require [cognitect.aws.credentials :as credentials]
            [cognitect.aws.ec2-metadata-utils-test :as ec2-metadata-utils-test]
            [clojure.test :refer :all]
            [clojure.java.io :as io]))

(use-fixtures :once ec2-metadata-utils-test/test-fixture)

(deftest chain-credentials-provider-test
  (let [cnt   (atom 0)
        p1    (reify credentials/CredentialsProvider
                (fetch [_]
                  (swap! cnt inc)
                  nil))
        creds {:aws/access-key-id     "id"
               :aws/secret-access-key "secret"}
        p2    (reify credentials/CredentialsProvider
                (fetch [_]
                  creds))
        cp    (credentials/chain-credentials-provider [p1 p2])]
    (testing "The chain provider calls each provider until one return credentials."
      (is (= creds (credentials/fetch cp)))
      (is (= 1 @cnt)))
    (testing "The provider is cached for future calls to `fetch`."
      (is (= creds (credentials/fetch cp)))
      (is (= 1 @cnt)))
    (testing "The chain provider returns nil if none of the providers returns credentials."
      (is (nil? (credentials/fetch (credentials/chain-credentials-provider [p1])))))))

(deftest environment-credentials-provider-test
  (testing "required vars present"
    (is (map? (credentials/fetch
               (credentials/environment-credentials-provider {"AWS_ACCESS_KEY_ID" "foo"
                                                              "AWS_SECRET_ACCESS_KEY" "bar"})))))
  (testing "required vars blank"
    (doall
     (for [env [{}
                {"AWS_ACCESS_KEY_ID" "foo"}
                {"AWS_SECRET_ACCESS_KEY" "bar"}
                {"AWS_ACCESS_KEY_ID" ""
                 "AWS_SECRET_ACCESS_KEY" "bar"}
                {"AWS_ACCESS_KEY_ID" "foo"
                 "AWS_SECRET_ACCESS_KEY" ""}]]
       (let [p (credentials/environment-credentials-provider env)]
         (is (nil? (credentials/fetch p))))))))

(deftest system-properites-credentials-provider-test
  (testing "required vars present"
    (is (map? (credentials/fetch
               (credentials/system-property-credentials-provider {"aws.accessKeyId" "foo"
                                                                  "aws.secretKey" "bar"})))))
  (testing "required vars blank"
    (doall
     (for [props [{}
                  {"aws.accessKeyId" "foo"}
                  {"aws.secretKey" "bar"}
                  {"aws.accessKeyId" ""
                   "aws.secretKey" "bar"}
                  {"aws.accessKeyId" "foo"
                   "aws.secretKey" ""}]]
       (let [p (credentials/system-property-credentials-provider props)]
         (is (nil? (credentials/fetch p))))))))

(deftest profile-credentials-provider-test
  (let [well-formed (io/file (io/resource "credentials/well-formed-config"))]
    (testing "The provider reads the default profile correctly."
      (is (= {:aws/access-key-id "DEFAULT_AWS_ACCESS_KEY"
              :aws/secret-access-key "DEFAULT_AWS_SECRET_ACCESS_KEY"
              :aws/session-token nil}
             (credentials/fetch (credentials/profile-credentials-provider "default" well-formed)))))
    (testing "The provider reads a custom profile correctly."
      (is (= {:aws/access-key-id "TARDIGRADE_AWS_ACCESS_KEY"
              :aws/secret-access-key "TARDIGRADE_AWS_SECRET_ACCESS_KEY"
              :aws/session-token "TARDIGRADE_AWS_SESSION_TOKEN"}
             (credentials/fetch (credentials/profile-credentials-provider "tardigrade" well-formed)))))))

(deftest container-credentials-provider-test
  (testing "The provider reads container metadata correctly."
    (let [env {credentials/ecs-container-credentials-path-env-var "/latest/meta-data/iam/security-credentials/"}]
      (is (= {:aws/access-key-id "foobar"
              :aws/secret-access-key "it^s4$3cret!"
              :aws/session-token "norealvalue"}
             (credentials/fetch (credentials/container-credentials-provider env)))))))

(deftest instance-profile-credentials-provider-test
  (testing "The provider reads ec2 metadata correctly."
    (is (= {:aws/access-key-id "foobar"
            :aws/secret-access-key "it^s4$3cret!"
            :aws/session-token "norealvalue"}
           (credentials/fetch (credentials/instance-profile-credentials-provider {}))))))

(deftest auto-refresh-test
  (let [cnt (atom 0)
        p (reify credentials/CredentialsProvider
            (credentials/fetch [_]
              (swap! cnt inc)
              {:aws/access-key-id "id"
               :aws/secret-access-key "secret"
               ::credentials/ttl 1}))
        creds (credentials/auto-refreshing-credentials p)]
    (Thread/sleep 5000)
    (let [refreshed @cnt]
      (credentials/stop creds)
      (Thread/sleep 2000)
      (is (<= 3 refreshed) "The credentials have been refreshed.")
      (is (= refreshed @cnt) "We stopped the auto-refreshing process."))))

(comment
  (run-tests))
