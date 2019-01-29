;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols.common
  "Impl, don't call directly. "
  (:require [clojure.data.json :as json]
            [cognitect.aws.util :as util]))

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

(defn parse-error*
  [{:keys [status] :as http-response} response-body]
  (with-meta (assoc response-body :cognitect.anomalies/category (status-code->anomaly status))
    http-response))

(defn xml-parse-error
  [{:keys [body] :as http-response}]
  (parse-error* http-response (some-> body util/bbuf->str util/xml-read util/xml->map)))

(defn json-parse-error
  [{:keys [body] :as http-response}]
  (parse-error* http-response (some-> body util/bbuf->str json/read-str)))
