;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.endpoint-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [cognitect.aws.endpoint :as endpoint]))

(def endpoints-excerpt
  {:partitions
   [{:defaults
     {:hostname          "{service}.{region}.{dnsSuffix}"
      :protocols         ["https"]
      :signatureVersions ["v4"]}
     :dnsSuffix     "amazonaws.com"
     :partition     "aws"
     :partitionName "AWS Standard"
     :regionRegex   "^(us|eu|ap|sa|ca)\\-\\w+\\-\\d+$"
     :regions
     {:us-east-1 {:description "US East (N. Virginia)"}}
     :services
     {:ec2
      {:defaults {:protocols ["http" "https"]}
       :endpoints
       {:us-east-1 {}}}
      :marketplacecommerceanalytics {:endpoints {:us-east-1 {}}}
      :iam
      {:endpoints
       {:aws-global
        {:credentialScope {:region "us-east-1"}
         :hostname        "iam.amazonaws.com"}}
       :isRegionalized    false
       :partitionEndpoint "aws-global"}}}]})

(deftest test-resolve-endpoints
  (testing "resolves regionalized endpoints"
    (with-redefs [endpoint/resolver (constantly endpoints-excerpt)]
      (is (= "ec2.us-east-1.amazonaws.com" (:hostname (endpoint/resolve :ec2 :us-east-1))))))
  (testing "resolves global endpoints"
    (with-redefs [endpoint/resolver (constantly endpoints-excerpt)]
      (is (= "iam.amazonaws.com"
             (:hostname (endpoint/resolve :iam :us-east-1)))))))
  (testing "uses defaults to resolve unspecified endpoints"
    (with-redefs [endpoint/resolver (constantly endpoints-excerpt)]
      (is (= "i-do-not-exist.us-east-1.amazonaws.com"
             (:hostname (endpoint/resolve :i-do-not-exist :us-east-1))))))

(comment
  (run-tests)

  )
