;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols.json
  "Impl, don't call directly."
  (:require [cognitect.aws.service :as service]
            [cognitect.aws.client :as client]
            [cognitect.aws.util :as util]
            [cognitect.aws.shape :as shape]
            [cognitect.aws.protocols.common :as common])
  (:import [java.util Date]))

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
        input-shape                        (service/shape service (:input operation))
        body                               (serialize nil input-shape (or request {}))]
    {:request-method :post
     :scheme         :https
     :server-port    443
     :uri            "/"
     :headers        {"x-amz-date"   (util/format-date util/x-amz-date-format (Date.))
                      "x-amz-target" (str targetPrefix "." (:name operation))
                      "content-type" (str "application/x-amz-json-" jsonVersion)}
     :body           (some-> body util/->bbuf)}))

(defmethod client/parse-http-response "json"
  [service {:keys [op] :as op-map} {:keys [status headers body] :as http-response}]
  (if (:cognitect.anomalies/category http-response)
    http-response
    (let [operation (get-in service [:operations op])
          output-shape (service/shape service (:output operation))]
      (let [body-str (util/bbuf->str body)]
        (if (< status 400)
          (shape/json-parse output-shape body-str)
          (common/json-parse-error http-response))))))
