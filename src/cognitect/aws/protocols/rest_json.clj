;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.protocols.rest-json
  "Impl, don't call directly."
  (:require [cognitect.aws.client :as client]
            [cognitect.aws.shape :as shape]
            [cognitect.aws.util :as util]
            [cognitect.aws.protocols.common :as common]
            [cognitect.aws.protocols.rest :as rest]))

(defmulti serialize
  (fn [_ shape data] (:type shape)))

(defmethod serialize :default
  [_ shape data]
  (shape/json-serialize shape data))

(defmethod serialize "structure"
  [_ shape data]
  (some->> (util/with-defaults shape data)
           not-empty
           (shape/json-serialize shape)))

(defmethod client/build-http-request "rest-json"
  [service op-map]
  (rest/build-http-request service
                           op-map
                           serialize))

(defmethod client/parse-http-response "rest-json"
  [service op-map http-response]
  (rest/parse-http-response service
                            op-map
                            http-response
                            shape/json-parse
                            common/json-parse-error))
