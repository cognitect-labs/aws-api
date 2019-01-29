;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.protocols.rest-json
  "Impl, don't call directly."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [cognitect.aws.client :as client]
            [cognitect.aws.shape :as shape]
            [cognitect.aws.util :as util]
            [cognitect.aws.protocols.common :as common]
            [cognitect.aws.protocols.rest :as rest]))

(defmulti serialize
  (fn [_ shape data] (:type shape)))

(defmethod serialize :default
  [_ shape data]
  (shape/json-serialize shape data))

(defmethod serialize "structure"
  [_ shape data]
  (some->> (util/with-defaults shape data)
           not-empty
           (shape/json-serialize shape)))

(defmethod serialize "timestamp"
  [_ shape data]
  (shape/format-date shape data))

(defmethod client/build-http-request "rest-json"
  [service op-map]
  (rest/build-http-request service
                           op-map
                           serialize))

(defmulti parser (fn [http-response] (get-in http-response [:headers "content-type"])))

(defmethod parser :default [_] shape/json-parse)

(defmethod parser "application/hal+json" [_]
  (fn [shape body-str]
    (when-not (str/blank? body-str)
      (let [data (json/read-str body-str :key-fn keyword)]
        (->> (into (dissoc data :_embedded :_links)
                   (some->> (get data :_embedded)
                            (reduce-kv (fn [m k v]
                                         (assoc m
                                                k
                                                (if (sequential? v)
                                                  (mapv #(dissoc % :_links) v)
                                                  (dissoc v :_links))))
                                       {})))
             ;; TODO (dchelimsky 2019-01-09) using json-parse* to
             ;; avoid str -> data -> str -> data, but maybe that fn
             ;; needs a diff name?
             (shape/json-parse* shape))))))

(defmethod client/parse-http-response "rest-json"
  [service op-map http-response]
  (rest/parse-http-response service
                            op-map
                            http-response
                            (parser http-response)
                            common/json-parse-error))
