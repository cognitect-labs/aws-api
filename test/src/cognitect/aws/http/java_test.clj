(ns cognitect.aws.http.java-test
  (:require [cognitect.aws.test.utils :as utils])
  (:import [java.net URI]
           [java.time Duration]
           [java.util Optional]))

(utils/when-java11

  (require '[cognitect.aws.http.java :as java-http-client]
           '[clojure.test :refer [deftest is testing]])
  (import '(jdk.internal.net.http RequestPublishers$ByteArrayPublisher RequestPublishers$EmptyPublisher)
          '(java.time Duration)
          '(java.io IOException)
          '(java.net.http HttpClient$Redirect)
          '[java.nio ByteBuffer])

  (deftest http-client-defaults-parity-with-cognitect-client
    (let [c (java-http-client/http-client)]
      ;; cognitect client defaults to 5 sec for name resolution timeout,
      ;; 5 sec for connection timeout. So go with a default of 10 here
      ;; which covers both.
      (is (= (Optional/of (Duration/ofMillis 10000)) (.connectTimeout c)))
      ;; Matches the configured behavior of cognitect client
      (is (= HttpClient$Redirect/NEVER (.followRedirects c)))))

  (deftest request->complete-uri-test
    (testing "Build URI"
      (let [request     {:scheme "http"
                         :server-name "localhost"
                         :server-port 8000
                         :uri "/api/uri"}]
        (is (= (URI/create "http://localhost:8000/api/uri")
               (java-http-client/request->complete-uri request)))))

    (testing "Build URI with query string"
      (let [request     {:server-name "localhost"
                         :server-port 8000
                         :uri "/api/uri"
                         :query-string "foo=bar"}]
        (is (= (URI/create "https://localhost:8000/api/uri?foo=bar")
               (java-http-client/request->complete-uri request)))))

    (testing "Build URI with no scheme, default is https"
      (let [request   {:server-name "localhost"
                       :server-port 8000
                       :uri "/api/uri"}]
        (is (= (URI/create "https://localhost:8000/api/uri")
               (java-http-client/request->complete-uri request)))))

    (testing "Build URI with no port"
      (let [request   {:server-name "localhost"
                       :uri "/api/uri"}]
        (is (= (URI/create "https://localhost/api/uri")
               (java-http-client/request->complete-uri request))))))

  (deftest body->body-publisher-test
    (testing "nil"
      (is (= RequestPublishers$EmptyPublisher
             (type (java-http-client/body->body-publisher nil)))))

    (testing "ByteBuffer"
      (is (= RequestPublishers$ByteArrayPublisher
             (type (java-http-client/body->body-publisher (ByteBuffer/wrap (.getBytes "body"))))))))

  (deftest remove-restricted-headers-test
    (testing "Remove restricted headers"
      (is (= {:foo "bar"
              :sectest "hey"
              :keep-alive "1"
              :sec-fetch-mode "stun"
              :proxy-test "something"}
             (java-http-client/remove-restricted-headers
              {:foo "bar"
               :sectest "hey"
               :content-length "42"
               :host "not allowed"
               :keep-alive "1"
               :sec-fetch-mode "stun"
               :expect "the unexpected"
               :upgrade "not allowed"
               :proxy-test "something"
               :connection "not allowed"}))))

    (testing "Remove restricted headers no matter the case"
      (is (= {:foo "bar"
              :sectest "hey"
              :sec-fetch-mode "stun"
              "Sec-Fetch-Site" "asdf"
              "Keep Alive"     "1"
              :proxy-test "something"
              "Proxy-A" "A"}
             (java-http-client/remove-restricted-headers
              {:foo             "bar"
               :Host            "not allowed"
               "Keep Alive"     "1"
               "Content-Length" "2112"
               :sectest         "hey"
               "Sec-Fetch-Site" "asdf"
               "Connection"     "not allowed"
               :sec-fetch-mode  "stun"
               :proxy-test      "something"
               "Proxy-A"        "A"})))))

  (deftest request->java-net-http-request-test
    (let [request {:scheme         "http"
                   :body           nil
                   :request-method :get
                   :timeout-msec   2000
                   :server-name    "server-test"
                   :server-port    8080
                   :uri            "/uri"
                   :query-string   "foo=bar"
                   :headers        {"header" "value" "thingy" "frob"}}
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
        (is (= {"header" ["value"]
                "thingy" ["frob"]}
               (-> java-net-http-request
                   .headers
                   .map))))))

  (deftest request->java-net-http-request-default-port-test
    (let [base-request {:request-method :get
                        :server-name    "server-test"
                        :uri            "/uri"}]

      (doseq [[opts expected] [; no explicit port
                               [{:scheme :http} "http://server-test/uri"]
                               [{:scheme :https} "https://server-test/uri"]
                               ; default scheme port
                               [{:scheme :http :server-port 80} "http://server-test/uri"]
                               [{:scheme :https :server-port 443} "https://server-test/uri"]
                               ; alternative port
                               [{:scheme :http :server-port 1234} "http://server-test:1234/uri"]
                               [{:scheme :https :server-port 1234} "https://server-test:1234/uri"]]]
        (let [java-net-http-request (java-http-client/request->java-net-http-request
                                      (merge base-request opts))]
          (testing (str "URI for " opts)
            (is (= expected
                   (-> java-net-http-request
                       .uri
                       .toString))))))))

  (deftest request->java-net-http-request-test-no-read-response-timeout-yields-no-timeout-set
    (let [request {:scheme         "http"
                   :body           nil
                   :request-method :get
                   :server-name    "server-test"
                   :server-port    8080
                   :uri            "/uri"
                   :query-string   "foo=bar"
                   :headers        {"header" "value"}}
          java-net-http-request (java-http-client/request->java-net-http-request request)]

      (testing "Timeout"
        (is (= (Optional/empty) (-> java-net-http-request .timeout))))))

  (deftest error->anomaly-test
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
               (:cognitect.anomalies/message anomaly)))))))
