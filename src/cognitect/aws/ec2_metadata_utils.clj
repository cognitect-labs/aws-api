;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.ec2-metadata-utils
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.core.async :as a]
            [cognitect.http-client :as http]
            [cognitect.aws.util :as util]
            [cognitect.aws.retry :as retry])
  (:import (java.net URI)))

(def ec2-metadata-service-override-system-property "com.amazonaws.sdk.ec2MetadataServiceEndpointOverride")
(def ec2-dynamicdata-root "/latest/dynamic/")
(def instance-identity-document "instance-identity/document")

(defn build-path [& components]
  (str/replace (str/join \/ components) #"\/\/+" (constantly "/")))

(defn get-host-address
  "Gets the EC2 metadata host address"
  []
  (or (System/getProperty "com.amazonaws.sdk.ec2MetadataServiceEndpointOverride")
      "http://169.254.169.254"))

(defn- build-uri
  [host path]
  (URI. (str host "/" (cond-> path (str/starts-with? path "/") (subs 1)))))

(defn- request-map
  [uri]
  {:scheme (.getScheme uri)
   :server-name (.getHost uri)
   :server-port (or (when (pos? (.getPort uri)) (.getPort uri)) (when (= (.getScheme uri) :https) 443) 80)
   :uri (.getPath uri)
   :request-method :get
   :headers {:accept "*/*"}})

(defn get-items
  "Takes a metadata server uri and returns a sequence of metadata items.
   Optionally, can take max retry attempts."
  [uri {:keys [retries split-lines]
        :or {retries 3
             split-lines true}
        :as options}]
  (let [response (a/<!! (retry/with-retry
                          #(http/submit (http/create {}) (request-map uri))
                          (a/promise-chan)
                          retry/default-retriable?
                          retry/default-backoff))]
    ;; TODO: handle unhappy paths -JS
    (if-not (:cognitect.anomalies/category response)
      (if (= (:status response) 200)
        (let [body-str (util/bbuf->str (:body response))]
          (if split-lines
            (str/split-lines body-str)
            [body-str]))
        nil)
      nil)))

(defn get-items-at-path
  ([path]
   (get-items-at-path path nil))
  ([path opts]
   (get-items (build-uri (get-host-address) path) opts)))

(defn get-data
  "Takes a metadata server uri and returns a single value."
  [uri]
  (first (get-items uri {:split-lines false})))

(defn get-data-at-path
  [path]
  (get-data (build-uri (get-host-address) path)))

(defn get-ec2-instance-data
  []
  (some-> (build-path ec2-dynamicdata-root instance-identity-document)
          get-data-at-path
          (json/read-str :key-fn keyword)))

(defn get-ec2-instance-region
  []
  (:region (get-ec2-instance-data)))
