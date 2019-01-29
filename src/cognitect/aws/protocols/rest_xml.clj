;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols.rest-xml
  "Impl, don't call directly."
  (:require [cognitect.aws.client :as client]
            [cognitect.aws.shape :as shape]
            [cognitect.aws.protocols.common :as common]
            [cognitect.aws.protocols.rest :as rest])
  (:import [java.util Date]))

(defmethod client/build-http-request "rest-xml"
  [{:keys [shapes operations metadata] :as service} op-map]
  (rest/build-http-request service
                           op-map
                           (fn [shape-name shape data]
                             (when data
                               (shape/xml-serialize shape
                                                    data
                                                    (or (:locationName shape) shape-name))))))

(defmethod client/parse-http-response "rest-xml"
  [service op-map http-response]
  (rest/parse-http-response service
                            op-map
                            http-response
                            shape/xml-parse
                            common/xml-parse-error))
