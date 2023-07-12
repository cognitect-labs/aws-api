;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols.rest-xml
  "Impl, don't call directly."
  (:require [cognitect.aws.protocols :as aws.protocols]
            [cognitect.aws.protocols.rest :as rest]
            [cognitect.aws.shape :as shape]))

(set! *warn-on-reflection* true)

(defn serialize
  "xml body args serializer passed to rest/build-http-request"
  [service shape-name shape data]
  (when data
    (shape/xml-serialize service
                         shape
                         data
                         (or (:locationName shape) shape-name))))

(defmethod aws.protocols/build-http-request "rest-xml"
  [service op-map]
  (rest/build-http-request service op-map serialize))

(defmethod aws.protocols/parse-http-response "rest-xml"
  [service op-map http-response]
  (rest/parse-http-response service
                            op-map
                            http-response
                            shape/xml-parse))
