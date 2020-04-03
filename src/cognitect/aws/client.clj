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
            [cognitect.aws.credentials :as credentials]
            [cognitect.aws.flow :as flow]))

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

(defn ^:private put-throwable [result-ch t response-meta op-map]
  (a/put! result-ch (with-meta
                      {:cognitect.anomalies/category :cognitect.anomalies/fault
                       ::throwable                   t}
                      (swap! response-meta
                             assoc :op-map op-map))))

(defn send-request
  "For internal use. Send the request to AWS and return a channel which delivers the response.

  Alpha. Subject to change."
  [client op-map]
  (let [{:keys [service http-client region-provider credentials-provider endpoint-provider]}
        (-get-info client)
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
            (let [http-request (sign-http-request service endpoint
                                                  creds
                                                  (-> (build-http-request service op-map)
                                                      (with-endpoint endpoint)
                                                      (update :body util/->bbuf)
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

(defn flow-request
  [client op-map]
  (let [executor (java.util.concurrent.ForkJoinPool/commonPool)
        submit! (fn [f]
                  (java.util.concurrent.CompletableFuture/supplyAsync
                   (reify java.util.function.Supplier
                     (get [_] (f)))
                   executor))
        {:keys [service http-client region-provider credentials-provider endpoint-provider]}
        (-get-info client)

        stk [{:name "fetch region"
              :f (fn [context]
                   (submit! #(assoc context :region (region/fetch region-provider))))}

             {:name "fetch credentials"
              :f (fn [context]
                   (submit! #(assoc context :credentials (credentials/fetch credentials-provider))))}

             {:name "discover endpoint"
              :f (fn [{:keys [region] :as context}]
                   (submit! #(assoc context :endpoint (endpoint/fetch endpoint-provider region))))}

             {:name "build http request"
              :f (fn [context]
                   (let [req (build-http-request service op-map)]
                     (assoc context :http-request req)))}

             {:name "add endpoint"
              :f (fn [context]
                   (update context :http-request with-endpoint (:endpoint context)))}

             {:name "body to ByteBuffer"
              :f #(update-in % [:http-request :body] util/->bbuf)}

             {:name "http interceptors"
              :f (fn [context]
                   (update context :http-request
                           (fn [r]
                             (interceptors/modify-http-request service op-map r))))}

             {:name "sign request"
              :f (fn [context]
                   (let [{:keys [endpoint credentials http-request]} context
                         signed (sign-http-request service endpoint credentials http-request)]
                     (assoc context :http-request signed)))}

             {:name "send request"
              :f (fn [context]
                   (let [cf (java.util.concurrent.CompletableFuture.)
                         resp-ch (http/submit http-client (:http-request context))
                         fulfill-future! (fn [response]
                                           (let [context (assoc context :http-response response)]
                                             (.complete cf context)))]
                     (a/take! resp-ch fulfill-future!)
                     cf))}

             {:name "decode response"
              :f (fn [context]
                   (let [{:keys [http-response]} context]
                     (assoc context :decoded
                            (handle-http-response service op-map http-response))))}]]
    (flow/execute-future {} stk)))

(comment
  (require '[cognitect.aws.client.api :as aws])
  (System/setProperty "aws.profile" "REDACTED")

  (def c (aws/client {:api :s3}))
  @(flow-request c {:op :ListBuckets})
  )
