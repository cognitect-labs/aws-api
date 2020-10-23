;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols.json
  "Impl, don't call directly."
  (:require [cognitect.aws.service :as service]
            [cognitect.aws.client :as client]
            [cognitect.aws.util :as util]
            [cognitect.aws.shape :as shape]
            [cognitect.aws.protocols.common :as common]))

(defmulti serialize
  (fn [_ shape data] (:type shape)))

(defmethod serialize :default
  [_ shape data]
  (shape/json-serialize shape data))

(defmethod serialize "structure"
  [_ shape data]
  (->> (util/with-defaults shape data)
       (shape/json-serialize shape)))

(defmethod client/build-http-request "json"
  [service {:keys [op request]}]
  (let [{:keys [jsonVersion targetPrefix]} (:metadata service)
        operation                          (get-in service [:operations op])
        input-shape                        (service/shape service (:input operation))]
    {:request-method :post
     :scheme         :https
     :server-port    443
     :uri            "/"
     :headers        (common/headers service operation)
     :body           (serialize nil input-shape (or request {}))}))

(defmethod client/parse-http-response "json"
  [service {:keys [op] :as op-map} {:keys [status headers body] :as http-response}]
  (if (:cognitect.anomalies/category http-response)
    http-response
    (let [operation (get-in service [:operations op])
          output-shape (service/shape service (:output operation))]
      (if (< status 400)
        (if output-shape
          (shape/json-parse output-shape body)
          {})
        (common/json-parse-error http-response)))))
