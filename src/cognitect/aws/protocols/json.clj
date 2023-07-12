;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols.json
  "Impl, don't call directly."
  (:require [cognitect.aws.protocols :as aws.protocols]
            [cognitect.aws.shape :as shape]
            [cognitect.aws.util :as util]))

(set! *warn-on-reflection* true)

(defmulti serialize
  (fn [_shapes shape _data] (:type shape)))

(defmethod serialize :default
  [shapes shape data]
  (shape/json-serialize shapes shape data))

(defmethod serialize "structure"
  [shapes shape data]
  (->> (util/with-defaults shape data)
       (shape/json-serialize shapes shape)))

(defmethod aws.protocols/build-http-request "json"
  [service {:keys [op request]}]
  (let [operation   (get-in service [:operations op])
        shapes      (:shapes service)
        input-shape (shape/resolve shapes (:input operation))]
    {:request-method :post
     :scheme         :https
     :server-port    443
     :uri            "/"
     :headers        (aws.protocols/headers service operation)
     :body           (serialize shapes input-shape (or request {}))}))

(defmethod aws.protocols/parse-http-response "json"
  [service {:keys [op]} {:keys [body]}]
  (let [operation    (get-in service [:operations op])
        shapes       (:shapes service)
        output-shape (shape/resolve shapes (:output operation))
        body-str     (util/bbuf->str body)]
    (if output-shape
      (shape/json-parse shapes output-shape body-str)
      {})))
