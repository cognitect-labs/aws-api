;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.client.api.async
  "API functions for using a client to interact with AWS services."
  (:require [clojure.core.async :as a]
            [cognitect.aws.client :as client]
            [cognitect.aws.service :as service]
            [cognitect.aws.util :as util]))

(def validate-requests? (atom {}))

(defn validate-requests [client tf]
  (swap! validate-requests? assoc client tf)
  (when tf
    (require [(service/spec-ns (-> client client/-get-info :service))]))
  tf)

(def ^:private registry-ref (delay (util/dynaload 'clojure.spec.alpha/registry)))
(defn registry [& args] (apply @registry-ref args))

(def ^:private valid?-ref (delay (util/dynaload 'clojure.spec.alpha/valid?)))
(defn valid? [& args] (apply @valid?-ref args))

(def ^:private explain-data-ref (delay (util/dynaload 'clojure.spec.alpha/explain-data)))
(defn explain-data [& args] (apply @explain-data-ref args))

(defn validate [service {:keys [op request] :or {request {}}}]
  (let [spec (service/request-spec-key service op)]
    (when (contains? (-> (registry) keys set) spec)
      (when-not (valid? spec request)
        (assoc (explain-data spec request)
               :cognitect.anomalies/category :cognitect.anomalies/incorrect)))))

(defn invoke
  "Just like cognitect.aws.client.api.async/invoke, except it returns
  a core.async channel which delivers the result.

  Additional supported keys in op-map:

  :ch - optional, channel to deliver the result"
  [client op-map]
  (let [result-chan                      (or (:ch op-map) (a/promise-chan))
        {:keys [service retry? backoff]} (client/-get-info client)
        validation-error                 (and (get @validate-requests? client)
                                              (validate service op-map))]
    (if validation-error
      (do
        (a/put! result-chan validation-error)
        result-chan)
      (let [send          #(client/send-request client op-map)
            retry?        (or (:retry? op-map) retry?)
            backoff       (or (:backoff op-map) backoff)
            response-chan (client/with-retry send
                            (a/promise-chan
                             (map #(with-meta (::client/result % %) (meta %))))
                            retry?
                            backoff)]
        (a/take! response-chan (partial a/put! result-chan))
        result-chan))))
