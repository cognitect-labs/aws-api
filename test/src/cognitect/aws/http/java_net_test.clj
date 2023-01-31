(ns cognitect.aws.http.java-net-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cognitect.aws.http.java-net :as java-http-client])
  (:import (jdk.internal.net.http RequestPublishers$EmptyPublisher RequestPublishers$ByteArrayPublisher RequestPublishers$StringPublisher RequestPublishers$PublisherAdapter)
           (java.time Duration)
           (java.io IOException)))

(deftest request->complete-uri-test
  (testing "Build URI"
    (let [request     {:scheme "http"
                       :server-name "localhost"
                       :server-port "8000"
                       :uri "/api/uri"}]
      (is (= "http://localhost:8000/api/uri"
             (java-http-client/request->complete-uri request)))))

  (testing "Build URI with query string"
    (let [request     {:server-name "localhost"
                       :server-port "8000"
                       :uri "/api/uri"
                       :query-string "foo=bar"}]
      (is (= "https://localhost:8000/api/uri?foo=bar"
             (java-http-client/request->complete-uri request)))))

  (testing "Build URI with no scheme, default is https"
    (let [request   {:server-name "localhost"
                     :server-port "8000"
                     :uri "/api/uri"}]
      (is (= "https://localhost:8000/api/uri"
             (java-http-client/request->complete-uri request)))))

  (testing "Build URI with no port"
    (let [request   {:server-name "localhost"
                     :uri "/api/uri"}]
      (is (= "https://localhost/api/uri"
             (java-http-client/request->complete-uri request))))))

(deftest body->body-publisher-test
  (testing "nil"
    (is (= RequestPublishers$EmptyPublisher
           (type (java-http-client/body->body-publisher nil)))))

  (testing "ByteArray"
    (is (= RequestPublishers$ByteArrayPublisher
           (type (java-http-client/body->body-publisher (byte-array 10))))))

  (testing "Not supported body"
    (is (= RequestPublishers$EmptyPublisher
           (type (java-http-client/body->body-publisher 123))))))

(deftest remove-restricted-headers-test
  (testing "Remove restricted headers"
    (is (= {:foo "bar"
            :sectest "hey"}
           (java-http-client/remove-restricted-headers
             {:foo "bar"
              :sectest "hey"
              :host "not allowed"
              :keep-alive "1"
              :sec-fetch-mode  "not allowed"
              :proxy-test "not allowed"}))))

  (testing "Remove restricted headers no matter the case"
    (is (= {:foo "bar"
            :sectest "hey"}
           (java-http-client/remove-restricted-headers
             {:foo             "bar"
              "Host"           "not allowed"
              "Keep Alive"     "1"
              :sectest         "hey"
              "Sec-Fetch-Site" "not allowed"
              :sec-fetch-mode  "not allowed"
              :proxy-test      "not allowed"
              "Proxy-A"        "not allowed"})))))

(deftest request->java-net-http-request-test
  (let [request {:scheme         "http"
                 :body           nil
                 :request-method :get
                 :timeout-msec   2000
                 :server-name    "server-test"
                 :server-port    8080
                 :uri            "/uri"
                 :query-string   "foo=bar"
                 :headers        {"header" "value"}}
        java-net-http-request (java-http-client/request->java-net-http-request request)]
    (testing "URI"
      (is (= "http://server-test:8080/uri?foo=bar"
             (-> java-net-http-request
                 .uri
                 .toString))))

    (testing "Method"
      (is (= "GET"
             (-> java-net-http-request
                 .method))))

    (testing "Timeout"
      (is (= (Duration/ofMillis 2000)
             (-> java-net-http-request
                 .timeout
                 .get))))

    (testing "Headers"
      (is (= {"header" ["value"]}
             (-> java-net-http-request
                 .headers
                 .map))))))

(deftest error->anomaly-test
  (testing "IOException"
    (let [exception (IOException. "oops!")
          anomaly (java-http-client/error->anomaly exception)]
      (is (= :cognitect.anomalies/fault
             (:cognitect.anomalies/category anomaly)))

      (is (= "oops!"
             (:cognitect.anomalies/message anomaly)))))

  (testing "IOException"
    (let [exception (IOException. "oops!")
          anomaly (java-http-client/error->anomaly exception)]
      (is (= :cognitect.anomalies/fault
             (:cognitect.anomalies/category anomaly)))

      (is (= "oops!"
             (:cognitect.anomalies/message anomaly)))))

  (testing "IllegalArgumentException"
    (let [exception (IllegalArgumentException. "oops!")
          anomaly (java-http-client/error->anomaly exception)]
      (is (= :cognitect.anomalies/incorrect
             (:cognitect.anomalies/category anomaly)))

      (is (= "oops!"
             (:cognitect.anomalies/message anomaly)))))

  (testing "SecurityException"
    (let [exception (SecurityException. "oops!")
          anomaly (java-http-client/error->anomaly exception)]
      (is (= :cognitect.anomalies/forbidden
             (:cognitect.anomalies/category anomaly)))

      (is (= "oops!"
             (:cognitect.anomalies/message anomaly))))))
