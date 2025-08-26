;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.ec2-metadata-utils
  "Impl, don't call directly"
  (:require [clojure.string :as str]
            [cognitect.aws.json :as json]
            [clojure.core.async :as a]
            [cognitect.aws.http :as http]
            [cognitect.aws.util :as u]
            [cognitect.aws.retry :as retry])
  (:import (java.net URI)))

(set! *warn-on-reflection* true)

(def ^:const ec2-metadata-service-override-system-property "com.amazonaws.sdk.ec2MetadataServiceEndpointOverride")
(def ^:const dynamic-data-root "/latest/dynamic/")
(def ^:const imds-v2-token-path "/latest/api/token")
(def ^:const security-credentials-path "/latest/meta-data/iam/security-credentials/")
(def ^:const instance-identity-document "instance-identity/document")

;; ECS
(def ^:const container-credentials-relative-uri-env-var "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI")
(def ^:const container-credentials-full-uri-env-var "AWS_CONTAINER_CREDENTIALS_FULL_URI")
(def ^:const container-authorization-token-env-var "AWS_CONTAINER_AUTHORIZATION_TOKEN")

(def ^:const ec2-metadata-host "http://169.254.169.254")
(def ^:const ecs-metadata-host "http://169.254.170.2")

(defn in-container? []
  (or (u/getenv container-credentials-relative-uri-env-var)
      (u/getenv container-credentials-full-uri-env-var)))

(defn build-path [& components]
  (str/replace (str/join \/ components) #"\/\/+" (constantly "/")))

(defn- build-uri
  [host path]
  (str host "/" (cond-> path (str/starts-with? path "/") (subs 1))))

(defn get-host-address
  "Gets the EC2 (or ECS) metadata host address"
  []
  (or (u/getProperty ec2-metadata-service-override-system-property)
      (when (in-container?) ecs-metadata-host)
      ec2-metadata-host))

(defn- request-map
  [^URI uri]
  (let [auth-token (u/getenv container-authorization-token-env-var)
        ;; matches the java sdk v2 default value
        ;; https://github.com/aws/aws-sdk-java-v2/blob/43950cfe9c067b56f3eedaa8c078432495be7c36/core/sdk-core/src/main/java/software/amazon/awssdk/core/SdkSystemSetting.java#L93-L101
        read-timeout-msec 1000]
    {:scheme (.getScheme uri)
     :server-name (.getHost uri)
     :server-port (or (when (pos? (.getPort uri)) (.getPort uri))
                      (when (#{"https"} (.getScheme uri)) 443)
                      80)
     :uri (.getPath uri)
     :request-method :get
     :headers (cond-> {"Accept" "*/*"}
                auth-token
                (assoc "Authorization" auth-token))
     :timeout-msec read-timeout-msec
     :cognitect.http-client/timeout-msec read-timeout-msec}))

(defn- get-response-data [request-map http-client]
  (let [response (a/<!! (retry/with-retry
                          #(http/submit http-client request-map)
                          (a/promise-chan)
                          retry/default-retriable?
                          retry/default-backoff))]
    ;; TODO: handle unhappy paths -JS
    (when (= 200 (:status response))
      (u/bbuf->str (:body response)))))

(defn ^:deprecated get-data [uri http-client]
  "DEPRECATED use `request-map`, `get-response-data`"
  (get-response-data (request-map (URI. uri)) http-client))

(defn ^:deprecated get-data-at-path [path http-client]
  "DEPRECATED use `build-uri`, `request-map`, `get-response-data`"
  (get-data (build-uri (get-host-address) path) http-client))

(defn ^:deprecated get-listing [uri http-client]
  "DEPRECATED use `get-listing-from-response`"
  (some-> (get-data uri http-client) str/split-lines))

(defn ^:deprecated get-listing-at-path [path http-client]
  "DEPRECATED use `get-listing-from-response`"
  (get-listing (build-uri (get-host-address) path) http-client))

(defn- add-header-when-truthy
  "Add a header to a request map IFF the header value is truthy, return the 'updated' request map,
  else return the unmodified request map."
  [request header value]
  (if value
    (assoc-in request [:headers header] value)
    request))

(defn- get-listing-from-response [data]
  (some-> data str/split-lines))

(defn get-ec2-instance-data
  ([http-client]
   (get-ec2-instance-data http-client nil))
  ([http-client IMDSv2-token]
   (some-> (get-host-address)
           (build-uri (build-path dynamic-data-root instance-identity-document))
           (URI.)
           (request-map)
           (add-header-when-truthy "X-aws-ec2-metadata-token" IMDSv2-token)
           (get-response-data http-client)
           (json/read-str :key-fn keyword))))

(defn get-ec2-instance-region
  ([http-client]
   (get-ec2-instance-region http-client nil))
  ([http-client IMDSv2-token]
   (:region (get-ec2-instance-data http-client IMDSv2-token))))

(defn container-credentials [http-client]
  (let [endpoint (or (when-let [path (u/getenv container-credentials-relative-uri-env-var)]
                       (str (get-host-address) path))
                     (u/getenv container-credentials-full-uri-env-var))]
    (some-> endpoint
            (URI.)
            (request-map)
            (get-response-data http-client)
            (json/read-str :key-fn keyword))))

(defn instance-credentials
  ([http-client]
   (instance-credentials http-client nil))
  ([http-client IMDSv2-token]
   (when (not (in-container?))
     (when-let [cred-name (-> (get-host-address)
                              (build-uri security-credentials-path)
                              (URI.)
                              (request-map)
                              (add-header-when-truthy "X-aws-ec2-metadata-token" IMDSv2-token)
                              (get-response-data http-client)
                              (get-listing-from-response)
                              first)]
       (some-> (get-host-address)
               (build-uri (str security-credentials-path cred-name))
               (URI.)
               (request-map)
               (add-header-when-truthy "X-aws-ec2-metadata-token" IMDSv2-token)
               (get-response-data http-client)
               (json/read-str :key-fn keyword))))))

(defn IMDSv2-token
  "Retrieve and return an IMDS v2 session token, or nil if IMDS v2 is not in effect."
  [http-client]
  (-> (get-host-address)
      (build-uri imds-v2-token-path)
      (URI.)
      (request-map)
      (assoc :request-method :put)
      (add-header-when-truthy "X-aws-ec2-metadata-token-ttl-seconds" "21600")
      (get-response-data http-client)))
