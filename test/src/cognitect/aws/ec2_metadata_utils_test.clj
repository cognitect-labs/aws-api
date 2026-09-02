;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.ec2-metadata-utils-test
  (:require [clojure.core.async :as a]
            [clojure.test :refer [deftest is testing]]
            [cognitect.aws.ec2-metadata-utils :as ec2-metadata-utils]
            [cognitect.aws.http :as http]
            [cognitect.aws.util :as u])
  (:import [java.io File]
           [java.net URI]))

(deftest returns-nil-after-retries
  ;; Using a mock http client that keeps track of the number of http requests and always returns a
  ;; busy response, assert that the expected number of requests were made and that ultimately a nil
  ;; response was returned.
  (let [expected-submit-count 4 ;; Expected to equal `max-retries` plus one.
        actual-submit-count (atom 0)
        response-chan (doto (a/promise-chan)
                        (a/>!! {:cognitect.anomalies/category :cognitect.anomalies/busy}))
        mock-http-client (reify http/HttpClient
                           (-submit [_ _request _channel]
                             (swap! actual-submit-count inc)
                             response-chan)
                           (-stop [_]))]
    (is (nil? (ec2-metadata-utils/get-ec2-instance-region mock-http-client)))
    (is (= expected-submit-count @actual-submit-count))))

(deftest request-map
  (testing "server-port"
    (is (= 443  (:server-port (#'ec2-metadata-utils/request-map (URI/create "https://169.254.169.254")))))
    (is (= 80   (:server-port (#'ec2-metadata-utils/request-map (URI/create "http://169.254.169.254")))))
    (is (= 8081 (:server-port (#'ec2-metadata-utils/request-map (URI/create "http://169.254.169.254:8081"))))))
  (testing "custom uris"
    (let [req-map (#'ec2-metadata-utils/request-map (URI/create "http://localhost:8080/get%20credentials?auth=123&data=a%20b"))]
      (is (= "/get%20credentials" (:uri req-map)))
      (is (= "auth=123&data=a%20b" (:query-string req-map)))))
  (testing "auth token"
    (is (nil? (get-in (#'ec2-metadata-utils/request-map (URI/create "http://localhost")) [:headers "Authorization"])))
    (with-redefs [u/getenv {"AWS_CONTAINER_AUTHORIZATION_TOKEN" "this-is-the-token"}]
      (is (#{"this-is-the-token"} (get-in (#'ec2-metadata-utils/request-map (URI/create "http://localhost")) [:headers "Authorization"]))))))

(deftest auth-token-file
  ;; https://docs.aws.amazon.com/eks/latest/userguide/pod-id-how-it-works.html#pod-id-agent-pod
  (let [token-file  (File/createTempFile "eks-pod-identity-token" "")
        auth-header #(get-in (#'ec2-metadata-utils/request-map (URI/create "http://169.254.170.23/v1/credentials"))
                             [:headers "Authorization"])]
    (try
      (spit token-file "eks-token-value\n")

      (testing "the token is read from the file named by AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE"
        (with-redefs [u/getenv {"AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE" (.getPath token-file)}]
          (is (= "eks-token-value" (auth-header)))))

      (testing "the file is re-read per request, so a rotated token is picked up"
        (with-redefs [u/getenv {"AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE" (.getPath token-file)}]
          (is (= "eks-token-value" (auth-header)))
          (spit token-file "rotated-token\n")
          (is (= "rotated-token" (auth-header)))))

      (testing "AWS_CONTAINER_AUTHORIZATION_TOKEN wins when both are set"
        ;; matches ContainerCredentialsProvider#getTokenValue in aws-sdk-java-v2
        (with-redefs [u/getenv {"AWS_CONTAINER_AUTHORIZATION_TOKEN"      "this-is-the-token"
                                "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE" (.getPath token-file)}]
          (is (= "this-is-the-token" (auth-header)))))

      (testing "an unreadable token file yields no Authorization header, and does not throw"
        (with-redefs [u/getenv {"AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE" (str (.getPath token-file) ".does-not-exist")}]
          (is (nil? (auth-header)))))

      (finally
        (.delete token-file)))))
