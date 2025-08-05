;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols
  "Impl, don't call directly. "
  (:require [cognitect.aws.json :as json]
            [clojure.string :as str]
            [cognitect.aws.util :as util])
  (:import (java.time ZoneOffset ZonedDateTime)))

(set! *warn-on-reflection* true)

(defmulti parse-http-response
  "HTTP response -> AWS response"
  (fn [service _op-map _http-response]
    (get-in service [:metadata :protocol])))

(defmulti build-http-request
  "AWS request -> HTTP request."
  (fn [service _op-map]
    (get-in service [:metadata :protocol])))

(defn ^:private status-code->anomaly-category [^long code]
  (case code
    304 :cognitect.anomalies/conflict
    403 :cognitect.anomalies/forbidden
    404 :cognitect.anomalies/not-found
    429 :cognitect.anomalies/busy
    503 :cognitect.anomalies/busy
    504 :cognitect.anomalies/unavailable
    (if (<= 300 code 499)
      :cognitect.anomalies/incorrect
      :cognitect.anomalies/fault)))

(defn sanitize-error-code
  "Per https://smithy.io/2.0/aws/protocols/aws-restjson1-protocol.html#operation-error-serialization:
    If a : character is present, then take only the contents before the first : character in the value.
    If a # character is present, then take only the contents after the first # character in the value."
  [error-code]
  (some-> error-code
          (str/split #":")
          first
          (str/split #"#" 2)
          last))

(defn error-code
  "Attempt to extract an error code from well known locations in an
   error response body. Returns nil if none are found.

   See:
     https://smithy.io/2.0/aws/protocols/aws-restjson1-protocol.html#operation-error-serialization
     https://smithy.io/2.0/aws/protocols/aws-json-1_0-protocol.html#operation-error-serialization
     https://smithy.io/2.0/aws/protocols/aws-json-1_1-protocol.html#operation-error-serialization
     https://smithy.io/2.0/aws/protocols/aws-restxml-protocol.html#error-response-serialization
     https://smithy.io/2.0/aws/protocols/aws-query-protocol.html#operation-error-serialization
     https://smithy.io/2.0/aws/protocols/aws-ec2-query-protocol.html#operation-error-serialization"
  [http-response]
  (or (-> http-response :headers (get "x-amzn-errortype"))
      (-> http-response :body :__type)
      (-> http-response :body :code)
      (-> http-response :body :Error :Code)
      (-> http-response :body :ErrorResponse :Error :Code)
      (-> http-response :body :Response :Errors :Error :Code)))

(defn ^:private error-code->anomaly-category
  "Given an error message extracted from an error response body *that we
   understand*, returns the appropriate anomaly category, or nil if none
   are found."
  [error-code]
  (condp = error-code
    "ThrottlingException" :cognitect.anomalies/busy
    nil))

(defn ^:private anomaly-category
  "Given an http-response with the body already coerced to a Clojure map,
   attempt to return an anomaly-category for a specific error message or
   status. Returns nil if none are found."
  [status sanitized-error-code]
  (or (error-code->anomaly-category sanitized-error-code)
      (status-code->anomaly-category status)))

(defn ^:private anomaly-message
  "Given 301 with an x-amz-bucket-region header, returns a clear message with direction
   for the user to resubmit the request to the correct region. Else returns nil."
  [response-map]
  (when-let [region (and (= 301 (:status response-map))
                         (get (:headers response-map) "x-amz-bucket-region"))]
    (str "The bucket is in this region: " region ". Please use this region to retry the request.")))

(defn headers [service operation]
  (let [{:keys [protocol targetPrefix jsonVersion]} (:metadata service)]
    (cond-> {"x-amz-date" (.format util/x-amz-date-format (ZonedDateTime/now ZoneOffset/UTC))}
      (contains? #{"json" "rest-json"} protocol)
      (assoc "x-amz-target" (str targetPrefix "." (:name operation))
             "content-type" (str "application/x-amz-json-" jsonVersion)
             ;; NOTE: apigateway returns application/hal+json unless
             ;; we specify the accept header
             "accept"       "application/json")
      (contains? #{"query" "ec2"} protocol)
      (assoc "content-type" "application/x-www-form-urlencoded; charset=utf-8"))))

(defn ^:private parse-encoded-string
  "Given non-nil String, determine the encoding (currently either XML or JSON). Return a Map
   representation of the encoded data.

   Returns nil if encoded-str is nil."
  [encoded-str]
  (when (seq encoded-str)
    (if (= \< (first encoded-str))
      (-> encoded-str util/xml-read util/xml->map)
      (-> encoded-str (json/read-str :key-fn keyword)))))

(defn parse-http-error-response
  "Given an http error response (any status code 300 or above), return an aws-api-specific response
  Map."
  [{:keys [status] :as http-response}]
  (let [http-response* (update http-response :body #(some-> % util/bbuf->str parse-encoded-string))
        sanitized-error-code (-> http-response* error-code sanitize-error-code)
        category (anomaly-category (:status http-response) sanitized-error-code)
        message (anomaly-message http-response*)]
    (with-meta
      (cond-> (assoc (:body http-response*) :cognitect.aws.http/status status)
        category (assoc :cognitect.anomalies/category category)
        message  (assoc :cognitect.anomalies/message message)
        sanitized-error-code (assoc :cognitect.aws.error/code sanitized-error-code))
      http-response)))
