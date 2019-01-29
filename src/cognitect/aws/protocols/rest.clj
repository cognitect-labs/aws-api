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
  (:import [java.util Date]))

;; ----------------------------------------------------------------------------------------
;; Serializer
;; ----------------------------------------------------------------------------------------

(defn serialize-uri
  "Take a URI template and a map of values and replace the parameters by their values.

  Example: (serialize-uri \"/{foo}\" {:foo \"bar\"}) -> \"/bar\""
  [uri-template args]
  (str/replace uri-template
               #"\{([^}]+)\}"
               (fn [[_ param]]
                 (or (if (.endsWith param "+")
                       (some-> args
                               (get (keyword (.substring param 0 (dec (count param)))))
                               util/url-encode
                               (.replace "%2F" "/")
                               (.replace "%7E" "~"))
                       (some-> args
                               (get (keyword param))
                               util/url-encode))
                     ""))))

(defmulti serialize-qs-args
  "Return a list of key-value pairs to serialize in the query string."
  (fn [shape args param-name] (:type shape)))

(defn append-querystring
  "Append the map of arguments args to the uri's querystring."
  [uri shape args]
  (if-let [qs (util/query-string (mapcat (fn [[k v]]
                                           (when-let [member-shape (shape/member-shape shape k)]
                                             (serialize-qs-args member-shape v (name k))))
                                         args))]
    (str uri (if (.contains uri "?") "&" "?") qs)
    uri))

(defmethod serialize-qs-args :default
  [shape args param-name]
  (when-not (nil? args)
    (let [param-name (or (:locationName shape) param-name)]
      [[param-name (str args)]])))

(defmethod serialize-qs-args "map"
  [shape args param-name]
  (let [key-shape (shape/key-shape shape)
        value-shape (shape/value-shape shape)]
    (mapcat (fn [[k v]]
              (serialize-qs-args value-shape v (name k)))
            args)))

(defmethod serialize-qs-args "list"
  [shape args param-name]
  (let [param-name (or (:locationName shape) param-name)]
    (mapcat #(serialize-qs-args (shape/list-member-shape shape) % param-name)
            args)))

(defmethod serialize-qs-args "timestamp"
  [shape args param-name]
  (when-not (nil? args)
    (let [param-name (or (:locationName shape) param-name)]
      [[param-name
        (shape/format-date shape args
                           (partial util/format-date util/iso8601-date-format))]])))

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
                   (assoc-in partition
                             [partition-key
                              (if (= :uri partition-key)
                                (or (keyword (:locationName member-shape))
                                    k)
                                k)]
                             v))
                 partition))
             {}
             (util/with-defaults shape args)))

(defn build-http-request
  [{:keys [shapes operations metadata] :as service} {:keys [op request] :as op-map} serialize-body-args]
  (let [operation (get operations op)
        input-shape-name (-> operation :input :shape)
        input-shape (service/shape service (:input operation))
        http-request {:request-method (-> operation :http :method str/lower-case keyword)
                      :scheme :https
                      :server-port 443
                      :uri (get-in operation [:http :requestUri])
                      :headers {"x-amz-date" (util/format-date util/x-amz-date-format (Date.))}}]
    (if-not input-shape
      http-request
      (let [location->args (partition-args input-shape request)
            body-args (:body location->args)]
        (-> http-request
            (update :uri serialize-uri (location->args :uri))
            (update :uri append-querystring input-shape (:querystring location->args))
            (update :headers merge (serialize-headers input-shape (merge (location->args :header)
                                                                         (location->args :headers))))
            (assoc :body
                   (util/->bbuf
                    (serialize-body input-shape-name input-shape body-args serialize-body-args))))))))

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
(defmethod parse-header-value "double"    [_ data] (Double. data))
(defmethod parse-header-value "float"     [_ data] (Double. data))
(defmethod parse-header-value "long"      [_ data] (Long. data))
(defmethod parse-header-value "integer"   [_ data] (Long. data))
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

(defn parse-body
  "Parse the HTTP response body for response data."
  [output-shape body parse-fn]
  (if-let [payload-name (:payload output-shape)]
    (let [body-shape (shape/member-shape output-shape (keyword payload-name))]
      (condp = (:type body-shape)
        "blob" {(keyword payload-name) (util/bbuf->input-stream body)}
        "string" (util/bbuf->str body)
        {(keyword payload-name) (parse-fn body-shape (util/bbuf->str body))}))
    ;; No payload
    (let [body-str (util/bbuf->str body)]
      (when-not (str/blank? body-str)
        (parse-fn output-shape body-str)))))

(defn parse-http-response
  [service {:keys [op] :as op-map} {:keys [status body] :as http-response}
   parse-body-str
   parse-error]
  (if (:cognitect.anomalies/category http-response)
    http-response
    (let [operation    (get-in service [:operations op])
          output-shape (service/shape service (:output operation))]
      (if (< status 400)
        (merge (parse-non-payload-attrs output-shape http-response)
               (when output-shape
                 (parse-body output-shape body parse-body-str)))
        (parse-error http-response)))))
