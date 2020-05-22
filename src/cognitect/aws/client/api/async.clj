;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.client.api.async
  "API functions for using a client to interact with AWS services."
  (:require [clojure.core.async :as a]
            [cognitect.aws.client :as client]
            [cognitect.aws.flow.default-stack :as default-stack]
            [cognitect.aws.flow.presigned-url-stack :as presigned-url-stack]
            [cognitect.aws.retry :as retry]
            [cognitect.aws.service :as service]
            [cognitect.aws.dynaload :as dynaload]))

(def ^:private validate-requests? (atom {}))

(defn ^:skip-wiki validate-requests
  "For internal use. Don't call directly."
  [client tf]
  (swap! validate-requests? assoc client tf)
  (when tf
    (service/load-specs (-> client client/-get-info :service)))
  tf)

(def ^:private registry-ref (delay (dynaload/load-var 'clojure.spec.alpha/registry)))
(defn ^:skip-wiki registry
  "For internal use. Don't call directly."
  [& args] (apply @registry-ref args))

(def ^:private valid?-ref (delay (dynaload/load-var 'clojure.spec.alpha/valid?)))
(defn ^:skip-wiki valid?
  "For internal use. Don't call directly."
  [& args] (apply @valid?-ref args))

(def ^:private explain-data-ref (delay (dynaload/load-var 'clojure.spec.alpha/explain-data)))
(defn ^:skip-wiki explain-data
  "For internal use. Don't call directly."
  [& args] (apply @explain-data-ref args))

(defn ^:skip-wiki validate
  "For internal use. Don't call directly."
  [service {:keys [op request] :or {request {}}}]
  (let [spec (service/request-spec-key service op)]
    (when (contains? (-> (registry) keys set) spec)
      (when-not (valid? spec request)
        (assoc (explain-data spec request)
               :cognitect.anomalies/category :cognitect.anomalies/incorrect)))))

(def ^{:private true :skip-wiki true} workflow-stacks
  {:cognitect.aws.alpha.workflow/default             default-stack/default-stack
   :cognitect.aws.alpha.workflow/presigned-url       presigned-url-stack/presigned-url-stack
   :cognitect.aws.alpha.workflow/fetch-presigned-url presigned-url-stack/fetch-presigned-url-stack})

(defn invoke
  "Async version of cognitect.aws.client.api/invoke. Returns
  a core.async channel which delivers the result.

  Additional supported keys in op-map:

  :ch - optional, channel to deliver the result

  Alpha. Subject to change."
  [client op-map]
  (let [workflow-steps                       (or (:workflow-steps op-map) ;; internal use only
                                                 (get workflow-stacks
                                                      (or (:workflow op-map) (:workflow client))
                                                      default-stack/default-stack))
        result-chan                          (or (:ch op-map) (a/promise-chan))
        {:keys [service retriable? backoff]} (client/-get-info client)
        validation-error                     (and (get @validate-requests? client)
                                                  (validate service op-map))]
    (if validation-error
      (a/put! result-chan validation-error)
      (retry/with-retry
        #(client/send-request client op-map workflow-steps)
        result-chan
        (or (:retriable? op-map) retriable?)
        (or (:backoff op-map) backoff)))
    result-chan))
