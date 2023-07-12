;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols.ec2
  "Impl, don't call directly."
  (:require [cognitect.aws.protocols :as aws.protocols]
            [cognitect.aws.protocols.query :as query]
            [cognitect.aws.shape :as shape]
            [cognitect.aws.util :as util]))

(set! *warn-on-reflection* true)

(defn serialized-name
  [shape default]
  (or (:queryName shape)
      (when-let [name (:locationName shape)]
        (apply str (Character/toUpperCase ^Character (first name)) (rest name)))
      default))

(defmulti serialize
  (fn [_shapes shape _args _serialized _prefix] (:type shape)))

(defmethod serialize :default
  [shapes shape args serialized prefix]
  (query/serialize shapes shape args serialized prefix))

(defmethod serialize "structure"
  [shapes shape args serialized prefix]
  (let [args (util/with-defaults shape args)]
    (reduce (fn [serialized k]
              (let [member-shape (shape/resolve shapes (shape/structure-member-shape-ref shape k))
                    member-name  (serialized-name member-shape (name k))]
                (if (contains? args k)
                  (serialize shapes member-shape (k args) serialized (conj prefix member-name))
                  serialized)))
            serialized
            (keys (:members shape)))))

(defmethod serialize "list"
  [shapes shape args serialized prefix]
  (let [member-shape (shape/resolve shapes (shape/list-member-shape-ref shape))]
    (reduce (fn [serialized [i member]]
              (serialize shapes member-shape member serialized (conj prefix (str i))))
            serialized
            (map-indexed (fn [i member] [(inc i) member]) args))))

(defmethod aws.protocols/build-http-request "ec2"
  [service op-map]
  (query/build-query-http-request op-map service serialize))

(defmethod aws.protocols/parse-http-response "ec2"
  [service op-map http-response]
  (query/build-query-http-response service op-map http-response))
