;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.test.ec2-metadata-utils-server
  "Modeled after com.amazonaws.util.EC2MetadataUtilsServer"
  (:require [clojure.data.json :as json]
            [cognitect.aws.client.shared :as shared]
            [cognitect.aws.ec2-metadata-utils :as ec2-metadata-utils]
            [cognitect.aws.util :as u]
            [org.httpkit.server :as http-server]))

(def iam-info
  (json/write-str
   {"Code" "Success"
    "LastUpdated" "2014-04-07T08 18 41Z"
    "InstanceProfileArn" "foobar"
    "InstanceProfileId" "moobily"
    "NewFeature" 12345}))

(def iam-cred-list
  "test1\ntest2")

(def iam-cred
  (json/write-str
   {"Code"  "Success"
    "LastUpdated" "2014-04-07T08:18:41Z"
    "Type" "AWS-HMAC"
    "AccessKeyId" "foobar"
    "SecretAccessKey" "it^s4$3cret!"
    "Token" "norealvalue"
    "Expiration" "2014-04-08T23:16:53Z"}))

(def instance-info
  (json/write-str
   {"pendingTime" "2014-08-07T22:07:46Z"
    "instanceType" "m1.small"
    "imageId" "ami-a49665cc"
    "instanceId" "i-6b2de041"
    "billingProducts" ["foo"]
    "architecture" "x86_64"
    "accountId" "599169622985"
    "kernelId" "aki-919dcaf8"
    "ramdiskId" "baz"
    "region" "us-east-1"
    "version" "2010-08-31"
    "availabilityZone" "us-east-1b"
    "privateIp" "10.201.215.38"
    "devpayProductCodes" ["bar"]}))

(def ^:dynamic *test-server-port*)
(def ^:private IMDSv2-token "a-secret-token")

(defn IMDSv2-secure?
  "Returns truthy value if the request contains the expected IMDSv2 token in its
  `X-aws-ec2-metadata-token` request header."
  [{{imds-v2-token "x-aws-ec2-metadata-token"} :headers}]
  (= IMDSv2-token imds-v2-token))

(defn route-IMDSv2
  "Routes for an IMDSv2-enabled test service."
  [{:keys [uri request-method] :as req}]
  (cond
    (and (= uri "/latest/api/token") (= :put request-method)) IMDSv2-token
    (and (IMDSv2-secure? req) (= uri "/latest/meta-data/iam/info")) iam-info
    (or (u/getenv ec2-metadata-utils/container-credentials-relative-uri-env-var)
        (u/getenv ec2-metadata-utils/container-credentials-full-uri-env-var)) iam-cred
    (and (IMDSv2-secure? req) (= uri "/latest/meta-data/iam/security-credentials/")) iam-cred-list
    (and (IMDSv2-secure? req) (re-find #"/latest/meta-data/iam/security-credentials/.+" uri)) iam-cred
    (and (IMDSv2-secure? req) (= uri "/latest/dynamic/instance-identity/document")) instance-info
    :else nil))

(defn route
  "Routes for a legacy IMDSv1 test service."
  [{:keys [uri]}]
  (cond
    (= uri "/latest/meta-data/iam/info") iam-info
    (or (u/getenv ec2-metadata-utils/container-credentials-relative-uri-env-var)
        (u/getenv ec2-metadata-utils/container-credentials-full-uri-env-var)) iam-cred
    (= uri "/latest/meta-data/iam/security-credentials/") iam-cred-list
    (re-find #"/latest/meta-data/iam/security-credentials/.+" uri) iam-cred
    (= uri "/latest/dynamic/instance-identity/document") instance-info
    :else nil))

(defn handler
  [{::keys [IMDSv2-enabled?]}]
  (let [route-fn (if IMDSv2-enabled? route-IMDSv2 route)]
    (fn [req]
      (let [resp-body (route-fn req)]
        {:status (if resp-body 200 404)
         :body resp-body}))))

(defn start
  "Starts a ec2 metadata utils server. Returns a no-arg stop function."
  [port opts]
  (http-server/run-server (handler opts) {:ip "127.0.0.1" :port port}))

(defn with-test-server*
  "Create a test server, at `localhost` and a random port number, to mock the metadata service which
  runs inside an ECS or EC2 host. Invoke `f` function parameter, presumably because the function
  invocation includes http client requests to the metadata service. `opts` is a map to configure the
  test server; the only current option is `::IMDSv2-enabled?` which, when set to a truthy value,
  causes the test server to act IMDS v2 compliant."
  [opts f]
  ;; NOTE: starting w/ 0 generates a random port
  (let [server-stop-fn   (start 0 opts)
        test-server-port (-> server-stop-fn meta :local-port)]
    (try
      (System/setProperty ec2-metadata-utils/ec2-metadata-service-override-system-property
                          (str "http://localhost:" test-server-port))
      (binding [*test-server-port* test-server-port]
        (f))
      (finally
        (server-stop-fn)
        (System/clearProperty ec2-metadata-utils/ec2-metadata-service-override-system-property)))))

(defmacro with-test-server
  [& body]
  `(with-test-server* {} (fn [] ~@body)))

(defmacro with-IMDSv2-test-server
  [& body]
  `(with-test-server* {::IMDSv2-enabled? true} (fn [] ~@body)))


(comment

  (def stop-fn (start 0 {}))

  (stop-fn))
