(ns cognitect.aws.http-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [cognitect.aws.http :as aws-http]))

(deftest configured-client-test
  (testing "Returns the default http client"
    (is (= 'cognitect.aws.http.default/create
           (#'aws-http/configured-client))))

  (testing "Returns new http client on cognitect_aws_http.edn file"
    (spit (io/file "test/resources/cognitect_aws_http.edn")
          "{:constructor-var cognitect.aws.http.java/create}")

    (is (= 'cognitect.aws.http.java/create
           (#'aws-http/configured-client)))

    (is (= true
         (io/delete-file "test/resources/cognitect_aws_http.edn" :failed))
        "Accidentally failed to delete file")))
