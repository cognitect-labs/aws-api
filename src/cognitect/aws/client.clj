;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.client
  "Impl, don't call directly."
  (:require [clojure.core.async :as a]
            [cognitect.aws.http :as http]
            [cognitect.aws.util :as util]
            [cognitect.aws.interceptors :as interceptors]
            [cognitect.aws.endpoint :as endpoint]
            [cognitect.aws.region :as region]
            [cognitect.aws.credentials :as credentials])
  (:import [java.util.concurrent Callable ExecutorService Executors ThreadFactory]))

(set! *warn-on-reflection* true)

(defprotocol ClientSPI
  (-get-info [_] "Intended for internal use only"))

(deftype Client [client-meta info]
  clojure.lang.IObj
  (meta [_] @client-meta)
  (withMeta [this m] (swap! client-meta merge m) this)

  ClientSPI
  (-get-info [_] info))

(defmulti build-http-request
  "AWS request -> HTTP request."
  (fn [service op-map]
    (get-in service [:metadata :protocol])))

(defmulti parse-http-response
  "HTTP response -> AWS response"
  (fn [service op-map http-response]
    (get-in service [:metadata :protocol])))

(defmulti sign-http-request
  "Sign the HTTP request."
  (fn [service endpoint credentials http-request]
    (get-in service [:metadata :signatureVersion])))

;; TODO convey throwable back from impl
(defn ^:private handle-http-response
  [service op-map http-response]
  (try
    (if (:cognitect.anomalies/category http-response)
      http-response
      (parse-http-response service op-map http-response))
    (catch Throwable t
      {:cognitect.anomalies/category :cognitect.anomalies/fault
       ::throwable t})))

(defn ^:private with-endpoint [req {:keys [protocol
                                           hostname
                                           port
                                           path]
                                    :as   endpoint}]
  (cond-> (-> req
              (assoc-in [:headers "host"] hostname)
              (assoc :server-name hostname))
    protocol (assoc :scheme protocol)
    port     (assoc :server-port port)
    path     (assoc :uri path)))

(defn send-request
  "Send the request to AWS and return a channel which delivers the response."
  [client op-map]
  (let [{:keys [service http-client region-provider credentials-provider endpoint-provider]}
        (-get-info client)
        ch       (a/promise-chan)
        err-meta (atom {})]
    (try
      (a/take!
       (region/fetch-async region-provider)
       (fn [region]
         (a/take!
          (credentials/fetch-async credentials-provider)
          (fn [creds]
            (let [endpoint     (endpoint/fetch endpoint-provider region)
                  http-request (sign-http-request service endpoint
                                                  creds
                                                  (-> (build-http-request service op-map)
                                                      (with-endpoint endpoint)
                                                      (update :body util/->bbuf)
                                                      ((partial interceptors/modify-http-request service op-map))))]
              (swap! err-meta assoc :http-request http-request)
              (a/take!
               (http/submit http-client http-request)
               (fn [response]
                 (a/put! ch (with-meta
                              (handle-http-response service op-map response)
                              {:http-request  http-request
                               :http-response (update response :body util/bbuf->input-stream)})))))))))
      ch
      (catch Throwable t
        (a/put! ch (with-meta
                     {:cognitect.anomalies/category :cognitect.anomalies/fault
                      ::throwable                   t}
                     (assoc @err-meta :op-map op-map)))
        ch))))
