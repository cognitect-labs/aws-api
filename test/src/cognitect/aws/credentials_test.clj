;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.credentials-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.tools.logging.test :refer [with-log logged?]]
            [cognitect.aws.client.shared :as shared]
            [cognitect.aws.credentials :as credentials]
            [cognitect.aws.util :as u]
            [cognitect.aws.test.utils :as tu]
            [cognitect.aws.ec2-metadata-utils :as ec2-metadata-utils]
            [cognitect.aws.test.ec2-metadata-utils-server :as ec2-metadata-utils-test-server])
  (:import (java.time Instant)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; tests

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

(deftest valid-credentials-test
  (with-log
    (credentials/valid-credentials nil "x provider")
    (is (logged? 'cognitect.aws.credentials :debug (str "Unable to fetch credentials from x provider."))))
  (with-log
    (credentials/valid-credentials {:aws/access-key-id     "id"
                                    :aws/secret-access-key "secret"}
                                   "x provider")
    (is (logged? 'cognitect.aws.credentials :debug (str "Fetched credentials from x provider.")))))

(deftest environment-credentials-provider-test
  (testing "required vars present"
    (with-redefs [u/getenv (tu/stub-getenv {"AWS_ACCESS_KEY_ID" "foo"
                                            "AWS_SECRET_ACCESS_KEY" "bar"})]
      (is (map? (credentials/fetch (credentials/environment-credentials-provider))))))
  (testing "required vars blank"
    (doall
     (for [env [{}
                {"AWS_ACCESS_KEY_ID" "foo"}
                {"AWS_SECRET_ACCESS_KEY" "bar"}
                {"AWS_ACCESS_KEY_ID" ""
                 "AWS_SECRET_ACCESS_KEY" "bar"}
                {"AWS_ACCESS_KEY_ID" "foo"
                 "AWS_SECRET_ACCESS_KEY" ""}]]
       (with-redefs [u/getenv (tu/stub-getenv env)]
         (let [p (credentials/environment-credentials-provider)]
           (is (nil? (credentials/fetch p)))))))))

(deftest system-properites-credentials-provider-test
  (testing "all vars present"
    (with-redefs [u/getProperty (tu/stub-getProperty {"aws.accessKeyId"  "foo"
                                                      "aws.secretKey"    "bar"
                                                      "aws.sessionToken" "baz"})]
      (is (= {:aws/access-key-id     "foo"
              :aws/secret-access-key "bar"
              :aws/session-token     "baz"}
             (credentials/fetch
              (credentials/system-property-credentials-provider))))))
  (testing "required vars present"
    (with-redefs [u/getProperty (tu/stub-getProperty {"aws.accessKeyId" "foo"
                                                      "aws.secretKey"   "bar"})]
      (is (= {:aws/access-key-id     "foo"
              :aws/secret-access-key "bar"
              :aws/session-token     nil}
             (credentials/fetch
              (credentials/system-property-credentials-provider))))))
  (testing "required vars blank"
    (doall
     (for [props [{}
                  {"aws.accessKeyId" "foo"}
                  {"aws.secretKey" "bar"}
                  {"aws.accessKeyId" ""
                   "aws.secretKey"   "bar"}
                  {"aws.accessKeyId" " "
                   "aws.secretKey"   "bar"}
                  {"aws.accessKeyId" "foo"
                   "aws.secretKey"   ""}
                  {"aws.accessKeyId" "foo"
                   "aws.secretKey"   " "}]]
       (with-redefs [u/getProperty (tu/stub-getProperty props)]
         (let [p (credentials/system-property-credentials-provider)]
           (is (nil? (credentials/fetch p)))))))))

(deftest profile-credentials-provider-test
  (let [test-config (io/file (io/resource ".aws/credentials"))]
    (testing "reads the default profile correctly."
      (is (= {:aws/access-key-id "DEFAULT_AWS_ACCESS_KEY"
              :aws/secret-access-key "DEFAULT_AWS_SECRET_ACCESS_KEY"
              :aws/session-token nil}
             (credentials/fetch (credentials/profile-credentials-provider "default" test-config)))))
    (testing "reads a custom profile correctly."
      (is (= {:aws/access-key-id "TARDIGRADE_AWS_ACCESS_KEY"
              :aws/secret-access-key "TARDIGRADE_AWS_SECRET_ACCESS_KEY"
              :aws/session-token "TARDIGRADE_AWS_SESSION_TOKEN"}
             (credentials/fetch (credentials/profile-credentials-provider "tardigrade" test-config)))))
    (testing "uses env vars and sys props for credentials file location and profile"
      (with-redefs [u/getenv (tu/stub-getenv {"AWS_CREDENTIAL_PROFILES_FILE" test-config})]
        (is (= {:aws/access-key-id "DEFAULT_AWS_ACCESS_KEY"
                :aws/secret-access-key "DEFAULT_AWS_SECRET_ACCESS_KEY"
                :aws/session-token nil}
               (credentials/fetch (credentials/profile-credentials-provider)))))
      (with-redefs [u/getenv (tu/stub-getenv {"AWS_CREDENTIAL_PROFILES_FILE" test-config
                                              "AWS_PROFILE" "tardigrade"})]
        (is (= {:aws/access-key-id "TARDIGRADE_AWS_ACCESS_KEY"
                :aws/secret-access-key "TARDIGRADE_AWS_SECRET_ACCESS_KEY"
                :aws/session-token "TARDIGRADE_AWS_SESSION_TOKEN"}
               (credentials/fetch (credentials/profile-credentials-provider)))))
      (with-redefs [u/getenv (tu/stub-getenv {"AWS_CREDENTIAL_PROFILES_FILE" test-config})
                    u/getProperty (tu/stub-getProperty {"aws.profile" "tardigrade"})]
        (is (= {:aws/access-key-id "TARDIGRADE_AWS_ACCESS_KEY"
                :aws/secret-access-key "TARDIGRADE_AWS_SECRET_ACCESS_KEY"
                :aws/session-token "TARDIGRADE_AWS_SESSION_TOKEN"}
               (credentials/fetch (credentials/profile-credentials-provider))))))
    (testing "uses source_profile."
      (is (= {:aws/access-key-id "DEFAULT_AWS_ACCESS_KEY"
              :aws/secret-access-key "DEFAULT_AWS_SECRET_ACCESS_KEY"
              :aws/session-token nil}
             (credentials/fetch (credentials/profile-credentials-provider "sourced" test-config)))))))

(deftest container-credentials-provider-test
  (testing "The provider reads container metadata correctly."
    (ec2-metadata-utils-test-server/with-test-server
      (with-redefs [u/getenv (tu/stub-getenv {ec2-metadata-utils/container-credentials-relative-uri-env-var
                                              ec2-metadata-utils/security-credentials-path})]
        (let [creds (credentials/fetch (credentials/container-credentials-provider
                                        (shared/http-client)))]
          (is (= {:aws/access-key-id "foobar"
                  :aws/secret-access-key "it^s4$3cret!"
                  :aws/session-token "norealvalue"}
                 (dissoc creds ::credentials/ttl)))
          (is (integer? (::credentials/ttl creds)))))
      (with-redefs [u/getenv (tu/stub-getenv {ec2-metadata-utils/container-credentials-full-uri-env-var
                                              (str "http://localhost:"
                                                   ec2-metadata-utils-test-server/*test-server-port*
                                                   ec2-metadata-utils/security-credentials-path)})]
        (let [creds (credentials/fetch (credentials/container-credentials-provider
                                        (shared/http-client)))]
          (is (= {:aws/access-key-id "foobar"
                  :aws/secret-access-key "it^s4$3cret!"
                  :aws/session-token "norealvalue"}
                 (dissoc creds ::credentials/ttl)))
          (is (integer? (::credentials/ttl creds))))))))

(deftest instance-profile-credentials-provider-test
  (testing "The provider reads ec2 metadata correctly."
    (ec2-metadata-utils-test-server/with-test-server
      (let [creds (credentials/fetch (credentials/instance-profile-credentials-provider
                                      (shared/http-client)))]
        (is (= {:aws/access-key-id "foobar"
                :aws/secret-access-key "it^s4$3cret!"
                :aws/session-token "norealvalue"}
               (dissoc creds ::credentials/ttl)))
        (is (integer? (::credentials/ttl creds)))))))

(deftest instance-profile-credentials-provider-test-not-IMDSv2-compliant
  (testing "The provider returns nil when IMDSv2 is enabled"
    (ec2-metadata-utils-test-server/with-IMDSv2-test-server
      (is (nil? (credentials/fetch (credentials/instance-profile-credentials-provider
                                    (shared/http-client))))))))

(deftest instance-profile-IMDSv2-credentials-provider-test
  (testing "The provider retrieves token then reads ec2 metadata correctly."
    (ec2-metadata-utils-test-server/with-IMDSv2-test-server
      (let [creds (credentials/fetch (credentials/instance-profile-IMDSv2-credentials-provider
                                      (shared/http-client)))]
        (is (= {:aws/access-key-id "foobar"
                :aws/secret-access-key "it^s4$3cret!"
                :aws/session-token "norealvalue"}
               (dissoc creds ::credentials/ttl)))
        (is (integer? (::credentials/ttl creds)))))))

(deftest auto-refresh-test ;; TODO flaky test
  (let [cnt (atom 0)
        p (reify credentials/CredentialsProvider
            (credentials/fetch [_]
              (swap! cnt inc)
              {:aws/access-key-id "id"
               :aws/secret-access-key "secret"
               ::credentials/ttl 1}))
        creds (credentials/cached-credentials-with-auto-refresh p)]
    (credentials/fetch creds)
    (Thread/sleep 2500)
    (let [refreshed @cnt]
      (credentials/stop creds)
      (Thread/sleep 1000)
      (is (= 3 refreshed) "The credentials have been refreshed.")
      (is (= refreshed @cnt) "We stopped the auto-refreshing process."))))

(deftest basic-credentials-provider
  (is (= {:aws/access-key-id "foo"
          :aws/secret-access-key "bar"}
         (credentials/fetch (credentials/basic-credentials-provider
                             {:access-key-id "foo"
                              :secret-access-key "bar"})))))

(defn minutes-from-now
  [m]
  (-> (Instant/now)
      (.plusSeconds (* m 60))
      (.with java.time.temporal.ChronoField/NANO_OF_SECOND 0)
      str))

(deftest ttl-calculation
  (testing "expiration defaults to an hour"
    (is (= 3600 (credentials/calculate-ttl {}))))
  (testing "refreshes in exp - 5 minutes"
    (let [c {:Expiration (minutes-from-now 60)}]
      (is (< (- (* 55 60) (* 5 60))
             (credentials/calculate-ttl c)
             (+ (* 55 60) (* 5 60))))))
  (testing "short expiration minimum is one minute"
    (let [c {:Expiration (minutes-from-now 3)}]
      (is (= 60 (credentials/calculate-ttl c)))))
  (testing "supports Instant instances"
    (let [c {:Expiration (Instant/parse (minutes-from-now 3))}]
      (is (= 60 (credentials/calculate-ttl c)))))
  (testing "supports java.util.Date (return value from sts :AssumeRole)"
    (let [c {:Expiration (java.util.Date/from (Instant/parse (minutes-from-now 3)))}]
      (is (= 60 (credentials/calculate-ttl c))))))

(comment
  (t/run-tests))
