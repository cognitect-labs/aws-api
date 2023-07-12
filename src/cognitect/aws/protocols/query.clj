;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols.query
  "Impl, don't call directly."
  (:require [clojure.string :as str]
            [cognitect.aws.protocols :as aws.protocols]
            [cognitect.aws.shape :as shape]
            [cognitect.aws.util :as util]))

(set! *warn-on-reflection* true)

; ----------------------------------------------------------------------------------------
;; Serializer
;; ----------------------------------------------------------------------------------------

(defn serialized-name
  [shape default]
  (or (:locationName shape)
      default))

(defn prefix-assoc
  [serialized prefix val]
  (assoc serialized (str/join "." prefix) val))

(defmulti serialize
  (fn [_shapes shape _args _serialized _prefix] (:type shape)))

(defmethod serialize :default
  [_shapes _shape args serialized prefix]
  (prefix-assoc serialized prefix (str args)))

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
  (if (empty? args)
    (prefix-assoc serialized prefix "")
    (let [member-shape (shape/resolve shapes (shape/list-member-shape-ref shape))
          list-prefix (if (:flattened shape)
                        (conj (vec (butlast prefix)) (serialized-name member-shape (last prefix)))
                        (conj prefix (serialized-name member-shape "member")))]
      (reduce (fn [serialized [i member]]
                (serialize shapes member-shape member serialized (conj list-prefix (str i))))
              serialized
              (map-indexed (fn [i member] [(inc i) member]) args)))))

(defmethod serialize "map"
  [shapes shape args serialized prefix]
  (let [map-prefix (if (:flattened shape) prefix (conj prefix "entry"))
        key-shape (shape/resolve shapes (shape/map-key-shape-ref shape))
        key-suffix (serialized-name key-shape "key")
        value-shape (shape/resolve shapes (shape/map-value-shape-ref shape))
        value-suffix (serialized-name value-shape "value")]
    (reduce (fn [serialized [i k v]]
              (as-> serialized $
                (serialize shapes key-shape (name k) $ (conj map-prefix (str i) key-suffix))
                (serialize shapes value-shape v $ (conj map-prefix (str i) value-suffix))))
            serialized
            (map-indexed (fn [i [k v]] [(inc i) k v]) args))))

(defmethod serialize "blob"
  [_shapes _shape args serialized prefix]
  (prefix-assoc serialized prefix (util/base64-encode args)))

(defmethod serialize "timestamp"
  [_shapes shape args serialized prefix]
  (prefix-assoc serialized prefix (shape/format-date shape
                                                     args
                                                     (partial util/format-date util/iso8601-date-format))))

(defmethod serialize "boolean"
  [_shapes _shape args serialized prefix]
  (prefix-assoc serialized prefix (if args "true" "false")))

(defn build-query-http-request
  [{:keys [op request]} service serialize]
  (let [operation   (get-in service [:operations op])
        shapes      (:shapes service)
        input-shape (shape/resolve shapes (:input operation))
        params      {"Action"  (name op)
                     "Version" (get-in service [:metadata :apiVersion])}]
    {:request-method :post
     :scheme         :https
     :server-port    443
     :uri            "/"
     :headers        (aws.protocols/headers service operation)
     :body           (util/query-string
                      (serialize shapes input-shape request params []))}))

(defmethod aws.protocols/build-http-request "query"
  [service op-map]
  (build-query-http-request op-map service serialize))

(defn build-query-http-response
  [service {:keys [op]} {:keys [body]}]
  (let [operation (get-in service [:operations op])
        shapes    (:shapes service)]
    (if-let [output-shape (shape/resolve shapes (:output operation))]
      (shape/xml-parse shapes output-shape (util/bbuf->str body))
      (util/xml->map (util/xml-read (util/bbuf->str body))))))

(defmethod aws.protocols/parse-http-response "query"
  [service op-map http-response]
  (build-query-http-response service op-map http-response))
