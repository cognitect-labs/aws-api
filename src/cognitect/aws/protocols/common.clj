;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols.common
  "Impl, don't call directly. "
  (:require [clojure.data.json :as json]
            [cognitect.aws.util :as util])
  (:import (java.util Date)))

(def status-codes->anomalies
  {403 :cognitect.anomalies/forbidden
   404 :cognitect.anomalies/not-found
   503 :cognitect.anomalies/busy
   504 :cognitect.anomalies/unavailable})

(defn status-code->anomaly [code]
  (or (get status-codes->anomalies code)
      (if (<= 400 code 499)
        :cognitect.anomalies/incorrect
        :cognitect.anomalies/fault)))

(defn headers [service operation]
  (let [{:keys [protocol targetPrefix jsonVersion]} (:metadata service)]
    (cond-> {"x-amz-date" (util/format-date util/x-amz-date-format (Date.))}
      (contains? #{"json" "rest-json"} protocol)
      (assoc "x-amz-target" (str targetPrefix "." (:name operation))
             "content-type" (str "application/x-amz-json-" jsonVersion))
      (contains? #{"query" "ec2"} protocol)
      (assoc "content-type" "application/x-www-form-urlencoded; charset=utf-8"))))

(defn parse-error*
  [{:keys [status] :as http-response} response-body]
  (with-meta (assoc response-body :cognitect.anomalies/category (status-code->anomaly status))
    http-response))

(defn xml-parse-error
  [{:keys [body] :as http-response}]
  (parse-error* http-response (some-> body util/xml-read util/xml->map)))

(defn json-parse-error
  [{:keys [body] :as http-response}]
  (parse-error* http-response (some-> body (json/read :key-fn keyword))))
