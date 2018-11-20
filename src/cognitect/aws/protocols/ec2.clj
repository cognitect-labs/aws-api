;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.protocols.ec2
  (:require [clojure.string :as str]
            [cognitect.aws.util :as util]
            [cognitect.aws.client :as client]
            [cognitect.aws.service :as service]
            [cognitect.aws.shape :as shape]
            [cognitect.aws.protocols.common :as common]
            [cognitect.aws.protocols.query :as query])
  (:import (java.util Date)))

(defn serialized-name
  [shape default]
  (or (:queryName shape)
      (when-let [name (:locationName shape)]
        (apply str (Character/toUpperCase (first name)) (rest name)))
      default))

(defmulti serialize
  (fn [shape args serialized prefix] (:type shape)))

(defmethod serialize :default
  [shape args serialized prefix]
  (query/serialize shape args serialized prefix))

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
  (let [member-shape (shape/list-member-shape shape)]
    (reduce (fn [serialized [i member]]
              (serialize member-shape member serialized (conj prefix (str i))))
            serialized
            (map-indexed (fn [i member] [(inc i) member]) args))))

(defmethod client/build-http-request "ec2"
  [service {:keys [op request]}]
  (let [operation   (get-in service [:operations op])
        input-shape (service/shape service (:input operation))
        params      {"Action"  op
                     "Version" (get-in service [:metadata :apiVersion])}]
    {:request-method :post
     :headers        {"x-amz-date"   (util/format-date util/x-amz-date-format (Date.))
                      "content-type" "application/x-www-form-urlencoded; charset=utf-8"}
     :scheme         :https
     :server-port    443
     :uri            "/"
     :body           (util/->bbuf
                      (str/join "&" (map (fn [[k v]] (str k "=" v))
                                         (serialize input-shape request params []))))}))

(defmethod client/parse-http-response "ec2"
  [service {:keys [op] :as op-map} {:keys [status body] :as http-response}]
  (if (:cognitect.anomalies/category http-response)
    http-response
    (let [operation (get-in service [:operations op])
          output-shape (service/shape service (:output operation))]
      (if (< status 400)
        (shape/xml-parse output-shape (util/bbuf->str body))
        (common/xml-parse-error http-response)))))
