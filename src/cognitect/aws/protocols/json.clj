;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols.json
  "Impl, don't call directly."
  (:require [cognitect.aws.protocols :as aws.protocols]
            [cognitect.aws.service :as service]
            [cognitect.aws.shape :as shape]
            [cognitect.aws.util :as util]))

(set! *warn-on-reflection* true)

(defmulti serialize
  (fn [shape _data] (:type shape)))

(defmethod serialize :default
  [shape data]
  (shape/json-serialize shape data))

(defmethod serialize "structure"
  [shape data]
  (->> (util/with-defaults shape data)
       (shape/json-serialize shape)))

(defmethod aws.protocols/build-http-request "json"
  [service {:keys [op request]}]
  (let [operation   (get-in service [:operations op])
        input-shape (service/shape service (:input operation))]
    {:request-method :post
     :scheme         :https
     :server-port    443
     :uri            "/"
     :headers        (aws.protocols/headers service operation)
     :body           (serialize input-shape (or request {}))}))

(defmethod aws.protocols/parse-http-response "json"
  [service {:keys [op]} {:keys [body]}]
  (let [operation (get-in service [:operations op])
        output-shape (service/shape service (:output operation))
        body-str (util/bbuf->str body)]
    (if output-shape
      (shape/json-parse output-shape body-str)
      {})))
