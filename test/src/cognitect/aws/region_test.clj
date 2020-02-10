;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.region-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.core.async :as a]
            [cognitect.aws.region :as region]
            [cognitect.aws.util :as u]
            [cognitect.aws.test.utils :as tu]
            [cognitect.aws.ec2-metadata-utils-test :as ec2-metadata-utils-test]))

(use-fixtures :once ec2-metadata-utils-test/test-server)

(deftest chain-region-provider-test
  (let [r  "us-east-1"
        p1 (reify region/RegionProvider (region/fetch [_]))
        p2 (reify region/RegionProvider (region/fetch [_] r))]
    (testing "provider calls each provider until one returns a region"
      (is (= r (region/fetch (region/chain-region-provider [p1 p2])))))
    (testing "provider throws if none of the providers returns a region."
      (is (thrown-with-msg? Exception #"No region found" (region/fetch (region/chain-region-provider [p1])))))))

(deftest profile-region-provider-test
  (let [config (io/file (io/resource ".aws/config"))]
    (testing "reads the default profile correctly."
      (is (= "us-east-1"
             (region/fetch (region/profile-region-provider "default" config)))))
    (testing "reads a custom profile correctly."
      (is (= "us-west-1"
             (region/fetch (region/profile-region-provider "tardigrade" config)))))

    (testing "uses env vars and sys props for credentials file location and profile"
      (with-redefs [u/getenv (tu/stub-getenv {"AWS_CONFIG_FILE" config})]
        (is (= "us-east-1" (region/fetch (region/profile-region-provider)))))
      (with-redefs [u/getenv (tu/stub-getenv {"AWS_CONFIG_FILE" config
                                              "AWS_PROFILE" "tardigrade"})]
        (is (= "us-west-1" (region/fetch (region/profile-region-provider)))))
      (with-redefs [u/getenv (tu/stub-getenv {"AWS_CONFIG_FILE" config})
                    u/getProperty (tu/stub-getProperty {"aws.profile" "tardigrade"})]
        (is (= "us-west-1" (region/fetch (region/profile-region-provider))))))))

(deftest instance-region-provider-test
  (testing "provider caches the fetched value"
    (let [orig-get-region-fn cognitect.aws.ec2-metadata-utils/get-ec2-instance-region
          request-counter    (atom 0)
          fetch-counter      (atom 0)]
      (with-redefs [cognitect.aws.ec2-metadata-utils/get-ec2-instance-region
                    (fn [http]
                      (swap! fetch-counter inc)
                      (orig-get-region-fn http))]
        (let [num-requests 10
              p            (region/instance-region-provider ec2-metadata-utils-test/*http-client*)
              chans        (repeatedly num-requests
                                       #(do
                                          (swap! request-counter inc)
                                          (region/fetch-async p)))]
          (is (apply = "us-east-1" (map #(a/<!! %) chans)))
          (is (= num-requests @request-counter))
          (is (= 1 @fetch-counter))))))())

(comment
  (run-tests)

  )
