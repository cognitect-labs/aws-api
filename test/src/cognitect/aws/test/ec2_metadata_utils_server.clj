(ns cognitect.aws.test.ec2-metadata-utils-server
  "Modeled after com.amazonaws.util.EC2MetadataUtilsServer"
  (:require [clojure.string :as str]
            [org.httpkit.server :as http-server]
            [clojure.data.json :as json]));

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

(defn route
  [uri]
  (cond
    (= uri "/latest/meta-data/iam/info") iam-info
    (= uri "/latest/meta-data/iam/security-credentials/") iam-cred-list
    (re-find #"/latest/meta-data/iam/security-credentials/.+" uri) iam-cred
    (= uri "/latest/dynamic/instance-identity/document") instance-info
    :default nil))
(defn handler
  [req]
  (let [resp-body (route (:uri req))]
    {:status (if resp-body 200 404)
     :body resp-body}))

(defn start
  "Starts a ec2 metadata utils server. Returns a no-arg stop function."
  [port]
  (http-server/run-server handler {:ip "127.0.0.1" :port port}))

(comment
  (def s (start 0))

  (s))


