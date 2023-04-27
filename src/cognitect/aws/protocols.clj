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

(def status-codes->anomalies
  {301 :cognitect.anomalies/incorrect
   403 :cognitect.anomalies/forbidden
   404 :cognitect.anomalies/not-found
   429 :cognitect.anomalies/busy
   503 :cognitect.anomalies/busy
   504 :cognitect.anomalies/unavailable})

(defn status-code->anomaly [code]
  (or (get status-codes->anomalies code)
      (if (<= 400 code 499)
        :cognitect.anomalies/incorrect
        :cognitect.anomalies/fault)))

(defn ^:private error-message
  "Attempt to extract an error message from well known locations in an
   error response map."
  [response-map]
  (or (:__type response-map)
      (:Code (:Error response-map))))

(defn ^:private error-message->anomaly
  "Given an error message extracted from an error response body *that we
   understand*, returns the appropriate anomaly category."
  [error-message]
  (condp = error-message
    "ThrottlingException" :cognitect.anomalies/busy
    nil))

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
  "Given an http error response (any status code 400 or above), return an aws-api-specific response
  Map."
  [{:keys [status body] :as http-response}]
  (let [response-map (some-> body util/bbuf->str parse-encoded-string*)
        category (or (error-message->anomaly (error-message response-map))
                     (status-code->anomaly status))]
    (with-meta
      (assoc response-map :cognitect.anomalies/category category)
      http-response)))
