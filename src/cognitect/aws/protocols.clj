;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols
  "Impl, don't call directly. "
  (:require [clojure.data.json :as json]
            [cognitect.aws.util :as util])
  (:import (java.util Date)))

(set! *warn-on-reflection* true)

(defmulti parse-http-response
  "HTTP response -> AWS response"
  (fn [service _op-map _http-response]
    (get-in service [:metadata :protocol])))

(defmulti build-http-request
  "AWS request -> HTTP request."
  (fn [service _op-map]
    (get-in service [:metadata :protocol])))

(defn ^:private status-code->anomaly-category [code]
  (case code
    403 :cognitect.anomalies/forbidden
    404 :cognitect.anomalies/not-found
    429 :cognitect.anomalies/busy
    503 :cognitect.anomalies/busy
    504 :cognitect.anomalies/unavailable
    (if (<= 300 code 499)
      :cognitect.anomalies/incorrect
      :cognitect.anomalies/fault)))

(defn ^:private error-message
  "Attempt to extract an error message from well known locations in an
   error response body. Returns nil if none are found."
  [response-map]
  (or (:__type response-map)
      (:Code (:Error response-map))))

(defn ^:private error-message->anomaly-category
  "Given an error message extracted from an error response body *that we
   understand*, returns the appropriate anomaly category, or nil if none
   are found."
  [error-message]
  (condp = error-message
    "ThrottlingException" :cognitect.anomalies/busy
    nil))

(defn ^:private anomaly-category
  "Given an http-response with the body already coerced to a Clojure map,
   attempt to return an anomaly-category for a specific error message or
   status. Returns nil if none are found."
  [{:keys [status body]}]
  (or (error-message->anomaly-category (error-message body))
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
    (cond-> {"x-amz-date" (util/format-date util/x-amz-date-format (Date.))}
      (contains? #{"json" "rest-json"} protocol)
      (assoc "x-amz-target" (str targetPrefix "." (:name operation))
             "content-type" (str "application/x-amz-json-" jsonVersion)
             ;; NOTE: apigateway returns application/hal+json unless
             ;; we specify the accept header
             "accept"       "application/json")
      (contains? #{"query" "ec2"} protocol)
      (assoc "content-type" "application/x-www-form-urlencoded; charset=utf-8"))))

(defn- parse-encoded-string*
  "Given non-nil String, determine the encoding (currently either XML or JSON). Return a Map
  representation of the encoded data."
  [encoded-str]
  (if (= \< (first encoded-str))
    (-> encoded-str util/xml-read util/xml->map)
    (-> encoded-str (json/read-str :key-fn keyword))))

(defn parse-http-error-response
  "Given an http error response (any status code 300 or above), return an aws-api-specific response
  Map."
  [http-response]
  (let [http-response* (update http-response :body #(some-> % util/bbuf->str parse-encoded-string*))
        category (anomaly-category http-response*)
        message (anomaly-message http-response*)]
    (with-meta
      (cond-> (:body http-response*)
        category (assoc :cognitect.anomalies/category category)
        message  (assoc :cognitect.anomalies/message message))
      http-response)))
