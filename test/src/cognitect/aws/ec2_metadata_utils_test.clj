;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.ec2-metadata-utils-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as a]
            [cognitect.http-client :as http]
            [cognitect.aws.test.ec2-metadata-utils-server :as ec2-metadata-utils-server]
            [cognitect.aws.ec2-metadata-utils :as ec2-metadata-utils]))

(def ^:dynamic *test-server-port*)

(defn test-fixture
  [f]
  ;; NOTE: starting w/ 0 generates a random port
  (let [server-stop-fn (ec2-metadata-utils-server/start 0)
        test-server-port (-> server-stop-fn meta :local-port)]
    (try
      (System/setProperty ec2-metadata-utils/ec2-metadata-service-override-system-property
                          (str "http://localhost:" test-server-port))
      (binding [*test-server-port* test-server-port]
        (f))
      (finally
        (server-stop-fn)
        (System/clearProperty ec2-metadata-utils/ec2-metadata-service-override-system-property)))))

(use-fixtures :once test-fixture)

(deftest get-ec2-instance-region
  (is (= "us-east-1" (ec2-metadata-utils/get-ec2-instance-region))))

(deftest returns-nil-after-retries
  (with-redefs [http/submit (constantly
                             (doto (a/promise-chan)
                               (a/>!! {:cognitect.anomalies/category :cognitect.anomalies/busy})))]
    (is (nil? (ec2-metadata-utils/get-ec2-instance-region)))))

(comment
  (run-tests)

  )
