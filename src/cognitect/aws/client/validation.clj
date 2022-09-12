;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.client.validation
  "For internal use. Don't call directly."
  (:require [cognitect.aws.client.protocol :as client.protocol]
            [cognitect.aws.dynaload :as dynaload]
            [cognitect.aws.service :as service]))

(set! *warn-on-reflection* true)

(defn validate-requests?
  "For internal use. Don't call directly."
  [client]
  (some-> client client.protocol/-get-info :validate-requests? deref))

(def ^:private registry-ref (delay (dynaload/load-var 'clojure.spec.alpha/registry)))
(defn registry
  "For internal use. Don't call directly."
  [& args] (apply @registry-ref args))

(def ^:private valid?-ref (delay (dynaload/load-var 'clojure.spec.alpha/valid?)))
(defn valid?
  "For internal use. Don't call directly."
  [& args] (apply @valid?-ref args))

(def ^:private explain-data-ref (delay (dynaload/load-var 'clojure.spec.alpha/explain-data)))
(defn explain-data
  "For internal use. Don't call directly."
  [& args] (apply @explain-data-ref args))

(defn request-spec
  "For internal use. Don't call directly."
  [service op]
  (when-let [spec (service/request-spec-key service op)]
    (when (contains? (-> (registry) keys set) spec)
      spec)))

(defn invalid-request-anomaly
  "For internal use. Don't call directly."
  [spec request]
  (assoc (explain-data spec request)
         :cognitect.anomalies/category :cognitect.anomalies/incorrect))

(defn unsupported-op-anomaly
  "For internal use. Don't call directly."
  [service op]
  {:cognitect.anomalies/category :cognitect.anomalies/unsupported
   :cognitect.anomalies/message "Operation not supported"
   :service (keyword (service/service-name service))
   :op op})