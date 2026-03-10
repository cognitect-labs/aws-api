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

(deftest uri-authority-test
  (testing "http scheme, default port"
    (is (= "a-server.example.com" (aws-http/uri-authority :http "a-server.example.com" 80)))
    (is (= "a-server.example.com" (aws-http/uri-authority :http "A-sErvEr.example.com" nil)))
    (is (= "a-server.example.com" (aws-http/uri-authority "HTTP" "a-server.example.com" 80)))
    (is (= "a-server.example.com" (aws-http/uri-authority "http" "a-server.example.com" nil))))

  (testing "https scheme, default port"
    (is (= "a-server.example.com" (aws-http/uri-authority :https "a-server.example.com" 443)))
    (is (= "a-server.example.com" (aws-http/uri-authority :https "A-sErvEr.example.com" nil)))
    (is (= "a-server.example.com" (aws-http/uri-authority "https" "a-server.example.com" 443)))
    (is (= "a-server.example.com" (aws-http/uri-authority "HTTPs" "a-server.example.com" nil))))

  (testing "http scheme, custom port"
    (is (= "a-server.example.com:8080" (aws-http/uri-authority :http "a-server.example.com" 8080)))
    (is (= "a-server.example.com:4080" (aws-http/uri-authority :http "a-server.example.com" 4080)))
    (is (= "a-server.example.com:443" (aws-http/uri-authority :http "a-server.example.com" 443)))
    (is (= "a-server.example.com:1" (aws-http/uri-authority :http "a-server.example.com" 1))))

  (testing "https scheme, custom port"
    (is (= "a-server.example.com:8080" (aws-http/uri-authority :https "a-server.example.com" 8080)))
    (is (= "a-server.example.com:4443" (aws-http/uri-authority :https "a-server.example.com" 4443)))
    (is (= "a-server.example.com:80" (aws-http/uri-authority :https "a-server.example.com" 80)))
    (is (= "a-server.example.com:1" (aws-http/uri-authority :https "a-server.example.com" 1)))))
