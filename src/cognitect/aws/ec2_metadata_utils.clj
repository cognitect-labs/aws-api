(ns cognitect.aws.ec2-metadata-utils
  (:require [cognitect.http-client :as http]
            [clojure.core.async :as async]
            [cognitect.aws.util :as util]
            [clojure.string :as str]
            [clojure.data.json :as json])
  (:import (java.net URI)))

(def ec2-metadata-service-override-system-property "com.amazonaws.sdk.ec2MetadataServiceEndpointOverride")
(def ec2-dynamicdata-root "/latest/dynamic/")
(def instance-identity-document "instance-identity/document")

(defn build-path [& components]
  (str/replace (str/join \/ components) #"\/\/+" (constantly "/")))

(defn exp-backoff-delays
  [min-wait retries]
  (map (partial * min-wait) (map #(Math/pow 2 %) (range retries))))

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

(defn retry?
  [http-response]
  (#{:cognitect.anomalies/busy :cognitect.anomalies/unavailable} (:cognitect.anomalies/category http-response)))

(defn get-items
  "Takes a metadata server uri and returns a sequence of metadata items.
   Optionally, can take max retry attempts."
  [uri {:keys [retries split-lines]
        :or {retries 3
             split-lines true}
        :as options}]
  (let [client (http/create {})
        request (request-map uri)
        response (loop [retry-delay (exp-backoff-delays 250 retries)]
                   (let [rsp (async/<!! (http/submit client request))]
                     (if (and (retry? rsp) (not-empty retry-delay))
                       (do
                         (Thread/sleep (first retry-delay))
                         (recur (next retry-delay)))
                       rsp)))]
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
