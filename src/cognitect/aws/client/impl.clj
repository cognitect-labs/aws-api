;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.client.impl
  "Impl, don't call directly."
  (:require [clojure.core.async :as a]
            [cognitect.aws.client.protocol :as client.protocol]
            [cognitect.aws.client.shared :as shared]
            [cognitect.aws.client.validation :as validation]
            [cognitect.aws.credentials :as credentials]
            [cognitect.aws.endpoint :as endpoint]
            [cognitect.aws.http :as http]
            [cognitect.aws.interceptors :as interceptors]
            [cognitect.aws.protocols :as aws.protocols]
            [cognitect.aws.region :as region]
            [cognitect.aws.retry :as retry]
            [cognitect.aws.signers :as signers]
            [cognitect.aws.util :as util])
  (:import (clojure.lang ILookup)))

(set! *warn-on-reflection* true)

;; TODO convey throwable back from impl
(defn ^:private handle-http-response
  [service op-map {:keys [status] :as http-response}]
  (try
    (if (:cognitect.anomalies/category http-response)
      http-response
      (if (< status 300)
        (aws.protocols/parse-http-response service op-map http-response)
        (aws.protocols/parse-http-error-response http-response)))
    (catch Throwable t
      {:cognitect.anomalies/category :cognitect.anomalies/fault
       ::throwable t})))

(defn ^:private with-endpoint
  "Updates the request map `req` with data returned by the endpoint provider.

   The request map is created with reasonable defaults by `cognitect.aws.protocols/build-http-request`,
   and the `:scheme`, `:server-port` and `:uri` may be overridden here with
   data from the endpoint provider.

   `:server-name` is always set based on the endpoint provider data.

   Also computes and sets the `Host` header with the appropriate authority."
  [req {:keys [protocol hostname port path]}]
  (let [scheme (or protocol (:scheme req) (throw (IllegalArgumentException. "missing protocol/scheme")))
        server-name (or hostname (throw (IllegalArgumentException. "missing hostname")))
        server-port (or port (:server-port req) (throw (IllegalArgumentException. "missing port/server-port")))
        uri (or path (:uri req) (throw (IllegalArgumentException. "missing path/uri")))
        authority (http/uri-authority scheme server-name server-port)]
    (-> req
        (assoc-in [:headers "host"] authority)
        (assoc :scheme scheme
               :server-name server-name
               :server-port server-port
               :uri uri))))

(defn ^:private put-throwable [result-ch t response-meta op-map]
  (a/put! result-ch (with-meta
                      {:cognitect.anomalies/category :cognitect.anomalies/fault
                       ::throwable                   t}
                      (swap! response-meta
                             assoc :op-map op-map))))

(defn ^:private send-request
  [client op-map http-request]
  (let [{:keys [service http-client region-provider credentials-provider endpoint-provider]}
        (client.protocol/-get-info client)
        response-meta (atom {})
        region-ch     (region/fetch-async region-provider)
        creds-ch      (credentials/fetch-async credentials-provider)
        response-ch   (a/chan 1)
        result-ch     (a/promise-chan)]
    (a/go
      (let [region   (a/<! region-ch)
            creds    (a/<! creds-ch)
            endpoint (endpoint/fetch endpoint-provider region)]
        (cond
          (:cognitect.anomalies/category region)
          (a/>! result-ch region)
          (:cognitect.anomalies/category creds)
          (a/>! result-ch creds)
          (:cognitect.anomalies/category endpoint)
          (a/>! result-ch endpoint)
          :else
          (try
            (let [http-request (signers/sign-http-request
                                service endpoint
                                creds
                                (-> http-request
                                    (with-endpoint endpoint)
                                    ((partial interceptors/modify-http-request service op-map))))]
              (swap! response-meta assoc :http-request http-request)
              (http/submit http-client http-request response-ch))
            (catch Throwable t
              (put-throwable result-ch t response-meta op-map))))))
    (a/go
      (try
        (let [response (a/<! response-ch)]
          (a/>! result-ch (with-meta
                            (handle-http-response service op-map response)
                            (swap! response-meta assoc
                                   :http-response (update response :body util/bbuf->input-stream)))))
        (catch Throwable t
          (put-throwable result-ch t response-meta op-map))))
    result-ch))

(defn ->Client [client-meta info]
  (with-meta
   (reify
     ILookup
     (valAt [this k]
       (.valAt this k nil))

     (valAt [this k default]
       (case k
         :api
         (-> info :service :metadata :cognitect.aws/service-name)
         :region
         (some-> info :region-provider region/fetch)
         :endpoint
         (some-> info :endpoint-provider (endpoint/fetch (.valAt this :region)))
         :credentials
         (some-> info :credentials-provider credentials/fetch)
         :service
         (some-> info :service (select-keys [:metadata]))
         :http-client
         (:http-client info)
         default))

     client.protocol/Client
     (-get-info [_] info)

     (-invoke [client op-map]
       (a/<!! (client.protocol/-invoke-async client op-map)))

     (-invoke-async [client {:keys [op request] :as op-map}]
       (let [result-chan (or (:ch op-map) (a/promise-chan))
             {:keys [service retriable? backoff]} (client.protocol/-get-info client)
             spec (and (validation/validate-requests? client) (validation/request-spec service op))]
         (cond
           (not (contains? (:operations service) (:op op-map)))
           (a/put! result-chan (validation/unsupported-op-anomaly service op))

           (and spec (not (validation/valid? spec request)))
           (a/put! result-chan (validation/invalid-request-anomaly spec request))

           :else
           ;; In case :body is an InputStream, ensure that we only read
           ;; it once by reading it before we send it to with-retry.
           (let [req (-> (aws.protocols/build-http-request service op-map)
                         (update :body util/->bbuf))]
             (retry/with-retry
              #(send-request client op-map req)
              result-chan
              (or (:retriable? op-map) retriable?)
              (or (:backoff op-map) backoff))))

         result-chan))

     (-stop [aws-client]
       (let [{:keys [http-client]} (client.protocol/-get-info aws-client)]
         (when-not (#'shared/shared-http-client? http-client)
           (http/stop http-client)))))
   (if (map? client-meta)
     client-meta
     ; backwards compatibility: `client-meta` used to be an atom
     (deref client-meta))))
