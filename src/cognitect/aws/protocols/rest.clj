;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols.rest
  "Impl, don't call directly.

  Common feature across the rest protocols (rest-json, rest-xml). "
  (:require [clojure.string :as str]
            [cognitect.aws.util :as util]
            [cognitect.aws.protocols.common :as common]
            [cognitect.aws.service :as service]
            [cognitect.aws.client :as client]
            [cognitect.aws.shape :as shape])
  (:import java.util.regex.Pattern))

;; ----------------------------------------------------------------------------------------
;; Serializer
;; ----------------------------------------------------------------------------------------

(defn- remove-leading-slash [s]
  (str/replace s #"^/" ""))

(defn serialize-uri
  "Take a URI template, an input-shape, and a map of values and replace the parameters by their values.
  Throws if args is missing any keys that are required in input-shape.

  Example URI template: /{Bucket}/{Key}"
  [uri-template {:keys [required members] :as input-shape} args s3?]
  (let [to-replace (filter (fn [[k {:keys [location]}]] (= "uri" location)) members)]
    (reduce (fn [uri [k {:keys [locationName]
                         :or {locationName (name k)}}]]
              (when (and (contains? (set required) (name k))
                         (not (find args k)))
                (throw (ex-info "Required key missing from request" {:required (mapv keyword required)})))
              (let [value (get args k)
                    encoded-value (-> value
                                      str
                                      (util/uri-encode s3?)
                                      remove-leading-slash)]
                (str/replace uri (Pattern/compile (str "\\{" locationName "\\+?""\\}")) encoded-value))) uri-template to-replace)))

(declare serialize-qs-args)

(defn append-querystring
  "Append the map of arguments args to the uri's querystring."
  [uri shape args]
  (if-let [qs (util/query-string (mapcat (fn [[k v]]
                                           (when-let [member-shape (shape/member-shape shape k)]
                                             (serialize-qs-args member-shape
                                                                (or (:locationName member-shape)
                                                                    (name k))
                                                                v)))
                                         args))]
    (str uri (if (.contains uri "?") "&" "?") qs)
    uri))

(defmulti serialize-qs-args
  "Return a list of key-value pairs to serialize in the query string."
  (fn [shape param-name args] (:type shape)))

(defmethod serialize-qs-args :default
  [shape param-name args]
  (when-not (nil? args)
    [[param-name (util/uri-encode (str args))]]))

(defmethod serialize-qs-args "list"
  [shape param-name args]
  (mapcat #(serialize-qs-args (shape/list-member-shape shape)
                              param-name
                              %)
          args))

(defmethod serialize-qs-args "map"
  [shape _ args]
  (mapcat (fn [[k v]]
            (serialize-qs-args (shape/value-shape shape)
                               (name k)
                               v))
          args))

(defmethod serialize-qs-args "timestamp"
  [shape param-name args]
  (when-not (nil? args)
    [[param-name
      (util/uri-encode
       (shape/format-date shape
                          args
                          (partial util/format-date util/iso8601-date-format)))]]))

(defmulti serialize-header-value
  "Serialize a primitive shape in a HTTP header."
  (fn [shape args] (:type shape)))

(defmethod serialize-header-value :default    [_ args] (str args))
(defmethod serialize-header-value "boolean"   [_ args] (if args "true" "false"))
(defmethod serialize-header-value "blob"      [_ args] (util/base64-encode args))
(defmethod serialize-header-value "timestamp" [shape args]
  (shape/format-date shape args
                     (partial util/format-date util/rfc822-date-format)))

(defn serialize-headers
  "Serialize the map of arguments into a map of HTTP headers."
  [shape args]
  (reduce-kv (fn [serialized k v]
               (let [member-shape (shape/member-shape shape k)
                     header-name (str/lower-case (or (:locationName member-shape) (name k)))]
                 (cond
                   (:jsonvalue member-shape)
                   (assoc serialized header-name (util/encode-jsonvalue v))

                   (map? v)
                   (reduce-kv (fn [serialized k v]
                                (let [header-name (str header-name (name k))]
                                  (assoc serialized header-name (serialize-header-value member-shape v))))
                              serialized
                              v)

                   :else
                   (assoc serialized header-name (serialize-header-value member-shape v)))))
             {}
             args))

(defn serialize-body
  [input-shape-name input-shape args serialize]
  (if-let [payload-name (:payload input-shape)]
    ;; A member of the input shape is flagged as the payload member.
    (let [payload-shape (shape/member-shape input-shape (keyword payload-name))]
      (if (contains? #{"blob" "string"} (:type payload-shape))
        ;; Streaming - return payload directly
        (get args (keyword payload-name))
        ;; Otherwise, serialize payload value to XML
        (when-let [body-arg (get args (keyword payload-name))]
          (serialize input-shape-name payload-shape body-arg))))
    ;; No payload attribute
    (serialize input-shape-name input-shape args)))

(defn partition-args
  "Partition the arguments by their location."
  [shape args]
  (reduce-kv (fn [partition k v]
               (if-let [member-shape (shape/member-shape shape k)]
                 (let [partition-key (or (keyword (:location member-shape)) :body)]
                   (assoc-in partition [partition-key k] v))
                 partition))
             {}
             (util/with-defaults shape args)))

(defn build-http-request
  [{:keys [shapes operations metadata] :as service} {:keys [op request] :as op-map} serialize-body-args]
  (let [operation        (get operations op)
        input-shape-name (-> operation :input :shape)
        input-shape      (service/shape service (:input operation))
        http-request     {:request-method (-> operation :http :method str/lower-case keyword)
                          :scheme         :https
                          :server-port    443
                          :uri            (get-in operation [:http :requestUri])
                          :headers        (common/headers service operation)}
        s3? (= "S3" (:serviceId metadata))]
    (if-not input-shape
      http-request
      (let [location->args (partition-args input-shape request)
            body-args      (:body location->args)]
        (-> http-request
            (update :uri serialize-uri input-shape (:uri location->args) s3?)
            (update :uri append-querystring input-shape (:querystring location->args))
            (update :headers merge (serialize-headers input-shape (merge (location->args :header)
                                                                         (location->args :headers))))
            (assoc :body (serialize-body input-shape-name input-shape body-args serialize-body-args)))))))

;; ----------------------------------------------------------------------------------------
;; Parser
;; ----------------------------------------------------------------------------------------

(defmulti parse-header-value
  "Parse a shape from an HTTP header value."
  (fn [shape data] (:type shape)))

(defmethod parse-header-value "string"    [shape data]
  (cond
    (nil? data)        ""
    (:jsonvalue shape) (util/parse-jsonvalue data)
    :else              data))
(defmethod parse-header-value "character" [_ data] (or data ""))
(defmethod parse-header-value "boolean"   [_ data] (= data "true"))
(defmethod parse-header-value "double"    [_ data] (Double/parseDouble ^String data))
(defmethod parse-header-value "float"     [_ data] (Double/parseDouble ^String data))
(defmethod parse-header-value "long"      [_ data] (Long/parseLong ^String data))
(defmethod parse-header-value "integer"   [_ data] (Long/parseLong ^String data))
(defmethod parse-header-value "blob"      [_ data] (util/base64-decode data))
(defmethod parse-header-value "timestamp"
  [shape data]
  (shape/parse-date shape data))

(defn parse-non-payload-attrs
  "Parse HTTP status and headers for response data."
  [{:keys [type members] :as output-shape} {:keys [status headers] :as http-response}]
  (reduce (fn [parsed member-key]
            (let [member-shape (shape/member-shape output-shape member-key)]
              (case (:location member-shape)
                "statusCode" (assoc parsed member-key status)
                "headers" (let [prefix (str/lower-case (or (:locationName member-shape) ""))
                                member-value (reduce-kv (fn [parsed k v]
                                                          (let [header-name (str/lower-case (name k))]
                                                            (if (.startsWith header-name prefix)
                                                              (assoc parsed
                                                                (keyword (.substring (name k) (count prefix)))
                                                                v)
                                                              parsed)))
                                                        {}
                                                        headers)]
                            (assoc parsed member-key member-value))
                "header" (let [header-name (str/lower-case (or (:locationName member-shape)
                                                               (name member-key)))]
                           (merge parsed
                                  (reduce-kv (fn [m k v]
                                               (if (= header-name (str/lower-case (name k)))
                                                 (assoc m member-key (parse-header-value member-shape v))
                                                 m))
                                             {}
                                             headers)))
                parsed)))
          {}
          (keys members)))

(defn parse-body*
  "Parse the HTTP response body for response data."
  [output-shape {:keys [body response-body-as headers]} parse-fn]
  (if-let [payload-name (some-> output-shape :payload keyword)]
    (let [body-shape (shape/member-shape output-shape payload-name)
          parsed-body (condp = (:type body-shape)
                        "blob" body ;; e.g. S3 GetObject
                        "string" (slurp body) ;; e.g. S3 GetBucketPolicy is a string payload
                        (parse-fn body-shape body))]
      {payload-name parsed-body})
    ;; No payload
    (do ;when (not= "0" (get headers "content-length"))
      (parse-fn output-shape body))))

(defn parse-http-response
  [service {:keys [op] :as op-map} {:keys [status body] :as http-response}
   parse-success
   parse-error]
  (if (:cognitect.anomalies/category http-response)
    http-response
    (let [operation    (get-in service [:operations op])
          output-shape (service/shape service (:output operation))]
      (if (< status 400)
        (merge (parse-non-payload-attrs output-shape http-response)
               (when output-shape
                 (parse-body* output-shape http-response parse-success)))
        (parse-error http-response)))))
