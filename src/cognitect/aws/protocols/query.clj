;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols.query
  "Impl, don't call directly."
  (:require [clojure.string :as str]
            [cognitect.aws.client :as client]
            [cognitect.aws.service :as service]
            [cognitect.aws.shape :as shape]
            [cognitect.aws.util :as util]
            [cognitect.aws.protocols.common :as common])
  (:import [java.util Date]))

; ----------------------------------------------------------------------------------------
;; Serializer
;; ----------------------------------------------------------------------------------------

(defn serialized-name
  [shape default]
  (or (:locationName shape)
      default))

(defmulti serialize
  (fn [shape args serialized prefix] (:type shape)))

(defn prefix-assoc
  [serialized prefix val]
  (assoc serialized (str/join "." prefix) val))

(defmethod serialize :default
  [shape args serialized prefix]
  (prefix-assoc serialized prefix (str args)))

(defmethod serialize "structure"
  [shape args serialized prefix]
  (let [args (util/with-defaults shape args)]
    (reduce (fn [serialized k]
              (let [member-shape (shape/member-shape shape k)
                    member-name  (serialized-name member-shape (name k))]
                (if (contains? args k)
                  (serialize member-shape (k args) serialized (conj prefix member-name))
                  serialized)))
            serialized
            (keys (:members shape)))))

(defmethod serialize "list"
  [shape args serialized prefix]
  (if (empty? args)
    (prefix-assoc serialized prefix "")
    (let [member-shape (shape/list-member-shape shape)
          list-prefix (if (:flattened shape)
                        (conj (vec (butlast prefix)) (serialized-name member-shape (last prefix)))
                        (conj prefix (serialized-name member-shape "member")))]
      (reduce (fn [serialized [i member]]
                (serialize member-shape member serialized (conj list-prefix (str i))))
              serialized
              (map-indexed (fn [i member] [(inc i) member]) args)))))

(defmethod serialize "map"
  [shape args serialized prefix]
  (let [map-prefix (if (:flattened shape) prefix (conj prefix "entry"))
        key-shape (shape/key-shape shape)
        key-suffix (serialized-name key-shape "key")
        value-shape (shape/value-shape shape)
        value-suffix (serialized-name value-shape "value")]
    (reduce (fn [serialized [i k v]]
              (as-> serialized $
                (serialize key-shape (name k) $ (conj map-prefix (str i) key-suffix))
                (serialize value-shape v $ (conj map-prefix (str i) value-suffix))))
            serialized
            (map-indexed (fn [i [k v]] [(inc i) k v]) args))))

(defmethod serialize "blob"
  [shape args serialized prefix]
  (prefix-assoc serialized prefix (util/base64-encode args)))

(defmethod serialize "timestamp" [shape args serialized prefix]
  (prefix-assoc serialized prefix (shape/format-date shape
                                                     args
                                                     (partial util/format-date util/iso8601-date-format))))

(defmethod serialize "boolean"
  [shape args serialized prefix]
  (prefix-assoc serialized prefix (if args "true" "false")))

(defn build-query-http-request
  [serialize service {:keys [op request]}]
  (let [operation   (get-in service [:operations op])
        input-shape (service/shape service (:input operation))
        params      {"Action"  (name op)
                     "Version" (get-in service [:metadata :apiVersion])}]
    {:request-method :post
     :scheme         :https
     :server-port    443
     :uri            "/"
     :headers        {"x-amz-date"   (util/format-date util/x-amz-date-format (Date.))
                      "content-type" "application/x-www-form-urlencoded; charset=utf-8"}
     :body           (util/->bbuf
                      (util/query-string
                       (serialize input-shape request params [])))}))

(defmethod client/build-http-request "query"
  [service req-map]
  (build-query-http-request serialize service req-map))

(defn build-query-http-response
  [service {:keys [op] :as op-map} {:keys [status body] :as http-response}]
  (let [operation (get-in service [:operations op])]
    (if (:cognitect.anomalies/category http-response)
      http-response
      (if (< status 400)
        (if-let [output-shape (service/shape service (:output operation))]
          (shape/xml-parse output-shape (util/bbuf->str body))
          (util/xml->map (util/xml-read (util/bbuf->str body))))
        (common/xml-parse-error http-response)))))

(defmethod client/parse-http-response "query"
  [service op-map http-response]
  (build-query-http-response service op-map http-response))
