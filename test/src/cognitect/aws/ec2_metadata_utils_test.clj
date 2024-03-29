;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.ec2-metadata-utils-test
  (:require [clojure.core.async :as a]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [cognitect.aws.client.shared :as shared]
            [cognitect.aws.ec2-metadata-utils :as ec2-metadata-utils]
            [cognitect.aws.http :as http]
            [cognitect.aws.test.ec2-metadata-utils-server :as ec2-metadata-utils-server]
            [cognitect.aws.util :as u])
  (:import [java.net URI]))

(def ^:dynamic *test-server-port*)
(def ^:dynamic *http-client*)

(defn test-server
  [f]
  ;; NOTE: starting w/ 0 generates a random port
  (let [server-stop-fn   (ec2-metadata-utils-server/start 0)
        test-server-port (-> server-stop-fn meta :local-port)]
    (try
      (System/setProperty ec2-metadata-utils/ec2-metadata-service-override-system-property
                          (str "http://localhost:" test-server-port))
      (binding [*test-server-port* test-server-port
                *http-client*      (shared/http-client)]
        (f))
      (finally
        (server-stop-fn)
        (System/clearProperty ec2-metadata-utils/ec2-metadata-service-override-system-property)))))

(use-fixtures :once test-server)

(deftest returns-nil-after-retries
  (with-redefs [http/submit (constantly
                             (doto (a/promise-chan)
                               (a/>!! {:cognitect.anomalies/category :cognitect.anomalies/busy})))]
    (is (nil? (ec2-metadata-utils/get-ec2-instance-region *http-client*)))))

(deftest request-map
  (testing "server-port"
    (is (= 443  (:server-port (#'ec2-metadata-utils/request-map (URI/create "https://169.254.169.254")))))
    (is (= 80   (:server-port (#'ec2-metadata-utils/request-map (URI/create "http://169.254.169.254")))))
    (is (= 8081 (:server-port (#'ec2-metadata-utils/request-map (URI/create "http://169.254.169.254:8081"))))))
  (testing "auth token"
    (is (nil? (get-in (#'ec2-metadata-utils/request-map (URI/create "http://localhost")) [:headers "Authorization"])))
    (with-redefs [u/getenv {"AWS_CONTAINER_AUTHORIZATION_TOKEN" "this-is-the-token"}]
      (is (#{"this-is-the-token"} (get-in (#'ec2-metadata-utils/request-map (URI/create "http://localhost")) [:headers "Authorization"]))))))
