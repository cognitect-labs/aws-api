;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.client.test-double
  "Provides a test implementation of the aws client, which can be passed
  to the functions in the cognitect.aws.client.api ns."
  (:require [clojure.core.async :as a]
            [cognitect.aws.client.protocol :as client.protocol]
            [cognitect.aws.client.validation :as validation]
            [cognitect.aws.service :as service])
  (:import (clojure.lang ILookup)))

(set! *warn-on-reflection* true)

(defn ^:private no-handler-provided-anomaly [op]
  {:cognitect.anomalies/category :cognitect.anomalies/incorrect
   :cognitect.anomalies/message  "No handler or response provided for op"
   :op op})

(defprotocol TestDoubleClient
  (-instrument [c ops]))

(deftype Client [info handlers]
  ILookup
  (valAt [this k]
    (.valAt this k nil))

  (valAt [_this k default]
    (case k
      :api
      (-> info :service :metadata :cognitect.aws/service-name)
      :service
      (:service info)
      default))

  client.protocol/Client
  (-get-info [_] info)

  (-invoke [this {:keys [op request] :as op-map}]
    (let [spec    (validation/request-spec (:service info) op)
          handler (get @handlers op)]
      (cond
        (not (get-in info [:service :operations op]))
        (validation/unsupported-op-anomaly (:service info) op)

        (and (validation/validate-requests? this)
             spec
             (not (validation/valid? spec request)))
        (validation/invalid-request-anomaly spec request)

        (not handler)
        (no-handler-provided-anomaly op)

        :else
        (handler op-map))))

  (-invoke-async [this {:keys [ch] :as op-map}]
    (let [response-chan (or ch (a/promise-chan))]
      (a/go
        (let [resp (.-invoke this op-map)]
          (a/>! response-chan resp)))
      response-chan))

  (-stop [_aws-client])
  
  TestDoubleClient
  (-instrument [client ops]
               (swap! (.handlers client)
                      (fn [handlers]
                        (reduce-kv
                         (fn [m op handler]
                           (when-not (some-> client :service  :operations op)
                             (throw (ex-info "Operation not supported"
                                             (validation/unsupported-op-anomaly (-> client :service) op))))
                           (assoc m op (if (fn? handler) handler (constantly handler))))
                         handlers
                         ops)))))

;; ->Client is intended for internal use
(alter-meta! #'->Client assoc :skip-wiki true)
(alter-meta! #'TestDoubleClient assoc :skip-wiki true)

(defn instrument
  "Given a test double client and a `:ops` map of operations to handlers,
   instruments the client with handlers. See `client` for more info about
   `:ops`."
  [client ops]
  (-instrument client ops))

(defn client
  "Given a map with :api and :ops (optional), returns a test double client that
  you can pass to `cognitect.aws.client.api/invoke` and `cognitect.aws.client.api/stop`
  in implementation code.
   
  You can provide :ops on creation or use `instrument` to add them later.

  :ops should be a map of operation (keyword) to one of
  - a function of op-map that returns a response map
  - a literal response map

  Notes:
  - you must instrument every op that will be invoked during a test
  - every op must be supported
    - See (keys (cognitect.aws.client.api/ops <test-client>))
  - will validate request payloads passed to `invoke` by default
    - you can disable request validation with (cognitect.aws.client.api/validate-requests client false)
  - will not validate response payloads"
  [{:keys [api ops]}]
  (let [service (service/service-description (name api))]
    (doto (->Client {:service service :validate-requests? (atom true)} (atom {}))
      (instrument ops))))
