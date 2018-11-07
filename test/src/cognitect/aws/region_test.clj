;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.region-test
  (:require [cognitect.aws.region :as region]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [cognitect.aws.ec2-metadata-utils-test :as ec2]))

(use-fixtures :once ec2/test-fixture)

(deftest chain-region-provider-test
  (let [r  "us-east-1"
        p1 (reify region/RegionProvider (region/fetch [_]))
        p2 (reify region/RegionProvider (region/fetch [_] r))]
    (testing "provider calls each provider until one returns a region"
      (is (= r (region/fetch (region/chain-region-provider [p1 p2])))))
    (testing "provider throws if none of the providers returns a region."
      (is (thrown-with-msg? Exception #"No region found" (region/fetch (region/chain-region-provider [p1])))))))

(deftest profile-region-provider-test
  (let [config (io/file (io/resource "region/.aws/config"))]
    (testing "The provider reads the default profile correctly."
      (is (= "us-east-1"
             (region/fetch (region/profile-region-provider "default" config)))))
    (testing "The provider reads a custom profile correctly."
      (is (= "us-west-1"
             (region/fetch (region/profile-region-provider "tardigrade" config)))))))

(deftest instance-region-provider-test
  (testing "The provider obtains the region from the instance metadata correctly."
    (is (= "us-east-1" (region/fetch (region/instance-region-provider))))))

(comment
  (run-tests))


