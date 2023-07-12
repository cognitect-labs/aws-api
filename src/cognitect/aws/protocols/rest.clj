;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols.rest
  "Impl, don't call directly.

  Common feature across the rest protocols (rest-json, rest-xml). "
  (:require [clojure.string :as str]
            [cognitect.aws.util :as util]
            [cognitect.aws.protocols :as aws.protocols]
            [cognitect.aws.service :as service]
            [cognitect.aws.shape :as shape]))

(set! *warn-on-reflection* true)

;; ----------------------------------------------------------------------------------------
;; Serializer
;; ----------------------------------------------------------------------------------------

(defn- remove-leading-slash [s]
  (str/replace s #"^/" ""))

(defn serialize-uri
  "Take a URI template, an input-shape, and a map of values and replace the parameters by their values.
  Throws if args is missing any keys that are required in input-shape."
  [uri-template {:keys [required] :as _input-shape} args]
  (str/replace uri-template
               #"\{([^}]+)\}"
               (fn [[_ ^String param]]
                 (or (if (.endsWith param "+")
                       (some-> args
                               (get (keyword (.substring param 0 (dec (count param)))))
                               util/url-encode
                               (str/replace "%2F" "/")
                               (str/replace "%7E" "~")
                               remove-leading-slash)
                       (some-> args
                               (get (keyword param))
                               str
                               util/url-encode
                               remove-leading-slash))
                     ;; TODO (dchelimsky 2019-02-08) it's possible that 100% of
                     ;; params in templated URIs are required, in which case
                     ;; we don't need this extra test.
                     (let [raw-param (str/replace param #"\+" "")]
                       (when (contains? (set required) raw-param)
                         (throw (ex-info "Required key missing from request. Check the docs for this operation."
                                         {:required (mapv keyword required)}))))
                     ""))))

(declare serialize-qs-args)

(defn append-querystring
  "Append the map of arguments args to the uri's querystring."
  [service ^String uri shape args]
  (if-let [qs (util/query-string (mapcat (fn [[k v]]
                                           (when-let [member-shape (shape/resolve service (shape/structure-member-shape-ref shape k))]
                                             (serialize-qs-args service
                                                                member-shape
                                                                (or (:locationName member-shape)
                                                                    (name k))
                                                                v)))
                                         args))]
    (str uri (if (.contains uri "?") "&" "?") qs)
    uri))

(defmulti serialize-qs-args
  "Return a list of key-value pairs to serialize in the query string."
  (fn [_service shape _param-name _args] (:type shape)))

(defmethod serialize-qs-args :default
  [_service _shape param-name args]
  (when-not (nil? args)
    [[param-name (str args)]]))

(defmethod serialize-qs-args "timestamp"
  [_service shape param-name args]
  (when-not (nil? args)
    [[param-name (shape/format-date shape
                                    args
                                    (partial util/format-date util/iso8601-date-format))]]))

(defmethod serialize-qs-args "list"
  [service shape param-name args]
  (mapcat #(serialize-qs-args service
                              (shape/resolve service (shape/list-member-shape-ref shape))
                              param-name
                              %)
          args))

(defmethod serialize-qs-args "map"
  [service shape _ args]
  (mapcat (fn [[k v]]
            (serialize-qs-args service
                               (shape/resolve service (shape/map-value-shape-ref shape))
                               (name k)
                               v))
          args))

(defmulti serialize-header-value
  "Serialize a primitive shape in a HTTP header."
  (fn [shape _args] (:type shape)))

(defmethod serialize-header-value :default    [_ args] (str args))
(defmethod serialize-header-value "boolean"   [_ args] (if args "true" "false"))
(defmethod serialize-header-value "blob"      [_ args] (util/base64-encode args))
(defmethod serialize-header-value "timestamp" [shape args]
  (shape/format-date shape args
                     (partial util/format-date util/rfc822-date-format)))

(defn serialize-headers
  "Serialize the map of arguments into a map of HTTP headers."
  [service shape args]
  (reduce-kv (fn [serialized k v]
               (let [member-shape (shape/resolve service (shape/structure-member-shape-ref shape k))
                     header-name (str/lower-case (or (:locationName member-shape) (name k)))]
                 (cond
                   (:jsonvalue member-shape)
                   (assoc serialized header-name (util/encode-jsonvalue v))

                   (#{"map" "structure"} (:type member-shape))
                   (reduce-kv (fn [serialized k v]
                                (let [header-name (str header-name (name k))]
                                  (assoc serialized header-name (serialize-header-value member-shape v))))
                              serialized
                              v)

                   (#{"list"} (:type member-shape))
                   (let [list-member-shape (shape/resolve service (shape/list-member-shape-ref member-shape))]
                     (assoc serialized header-name (str/join "," (map (partial serialize-header-value list-member-shape) v))))

                   :else
                   (assoc serialized header-name (serialize-header-value member-shape v)))))
             {}
             args))

(defn serialize-body
  [service input-shape-name input-shape args serialize]
  (if-let [payload-name (:payload input-shape)]
    ;; A member of the input shape is flagged as the payload member.
    (let [payload-shape (shape/resolve service (shape/structure-member-shape-ref input-shape (keyword payload-name)))]
      (if (contains? #{"blob" "string"} (:type payload-shape))
        ;; Streaming - return payload directly
        (get args (keyword payload-name))
        ;; Otherwise, serialize payload value to XML
        (when-let [body-arg (get args (keyword payload-name))]
          (serialize service input-shape-name payload-shape body-arg))))
    ;; No payload attribute
    (serialize service input-shape-name input-shape args)))

(defn partition-args
  "Partition the arguments by their location."
  [service shape args]
  (reduce-kv (fn [partition k v]
               (if-let [member-shape (shape/resolve service (shape/structure-member-shape-ref shape k))]
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
  [{:keys [operations] :as service} {:keys [op request]} serialize-body-args]
  (let [operation        (get operations op)
        input-shape-name (-> operation :input :shape)
        input-shape      (shape/resolve service (:input operation))
        http-request     {:request-method (-> operation :http :method str/lower-case keyword)
                          :scheme         :https
                          :server-port    443
                          :uri            (get-in operation [:http :requestUri])
                          :headers        (aws.protocols/headers service operation)}]
    (if-not input-shape
      http-request
      (let [location->args (partition-args service input-shape request)
            body-args      (:body location->args)]
        (-> http-request
            (update :uri serialize-uri input-shape (:uri location->args))
            (update :uri (partial append-querystring service) input-shape (:querystring location->args))
            (update :headers merge (serialize-headers service input-shape (merge (location->args :header)
                                                                                 (location->args :headers))))
            (assoc :body (serialize-body service input-shape-name input-shape body-args serialize-body-args)))))))

;; ----------------------------------------------------------------------------------------
;; Parser
;; ----------------------------------------------------------------------------------------

(defmulti parse-header-value
  "Parse a shape from an HTTP header value."
  (fn [shape _data] (:type shape)))

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
  [service {:keys [members] :as output-shape} {:keys [status headers] :as _http-response}]
  (reduce (fn [parsed member-key]
            (let [member-shape (shape/resolve service (shape/structure-member-shape-ref output-shape member-key))]
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
  [service output-shape body parse-body-str]
  (if-let [payload-name (:payload output-shape)]
    (let [body-shape (shape/resolve service (shape/structure-member-shape-ref output-shape (keyword payload-name)))]
      {(keyword payload-name) (condp = (:type body-shape)
                                "blob"   (util/bbuf->input-stream body)
                                "string" (util/bbuf->str body)
                                (parse-body-str service body-shape (util/bbuf->str body)))})
    ;; No payload
    (let [body-str (util/bbuf->str body)]
      (when-not (str/blank? body-str)
        (parse-body-str service output-shape body-str)))))

(defn parse-http-response
  [service {:keys [op]} {:keys [body] :as http-response} parse-body-str]
  (let [operation (get-in service [:operations op])
        output-shape (shape/resolve service (:output operation))]
    (merge (parse-non-payload-attrs service output-shape http-response)
           (when output-shape
             (parse-body service output-shape body parse-body-str)))))
