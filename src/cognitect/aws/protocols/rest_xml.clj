;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols.rest-xml
  "Impl, don't call directly."
  (:require [cognitect.aws.client :as client]
            [cognitect.aws.shape :as shape]
            [cognitect.aws.protocols.common :as common]
            [cognitect.aws.protocols.rest :as rest]))

(set! *warn-on-reflection* true)

(defn serialize
  "xml body args serializer passed to rest/build-http-request"
  [shape-name shape data]
  (when data
    (shape/xml-serialize shape
                         data
                         (or (:locationName shape) shape-name))))

(defmethod client/build-http-request "rest-xml"
  [service op-map]
  (rest/build-http-request service op-map serialize))

(defmethod client/parse-http-response "rest-xml"
  [service op-map http-response]
  (rest/parse-http-response service
                            op-map
                            http-response
                            shape/xml-parse
                            common/xml-parse-error))
