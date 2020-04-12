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

(def fetch-region-step
  {:name "fetch region"
   :f (fn [{:keys [submit! region-provider] :as context}]
        (submit! #(assoc context :region (region/fetch region-provider))))})

(def fetch-credentials-step
  {:name "fetch credentials"
   :f (fn [{:keys [submit! credentials-provider] :as context}]
        (submit! #(assoc context :credentials (credentials/fetch credentials-provider))))})

(def discover-endpoint-step
  {:name "discover endpoint"
   :f (fn [{:keys [submit! endpoint-provider region] :as context}]
        (submit! #(assoc context :endpoint (endpoint/fetch endpoint-provider region))))})

(def build-http-request-step
  {:name "build http request"
   :f (fn [{:keys [service op-map] :as context}]
        (assoc context :http-request (build-http-request service op-map)))})

(def add-endpoint-step
  {:name "add endpoint"
   :f (fn [{:keys [endpoint] :as context}]
        (update context :http-request with-endpoint endpoint))})

(def body-to-byte-buffer-step
  {:name "body to ByteBuffer"
   :f #(update-in % [:http-request :body] util/->bbuf)})

(def http-interceptors-step
  {:name "http interceptors"
   :f (fn [{:keys [service op-map] :as context}]
        (update context :http-request
                (fn [r]
                  (interceptors/modify-http-request service op-map r))))})

(def sign-request-step
  {:name "sign request"
   :f (fn [{:keys [service endpoint credentials http-request] :as  context}]
        (let [
              signed (sign-http-request service endpoint credentials http-request)]
          (assoc context :http-request signed)))})

(def send-request-step
  {:name "send request"
   :f (fn [{:keys [http-client http-request] :as context}]
        (let [cf (java.util.concurrent.CompletableFuture.)
              resp-ch (http/submit http-client http-request)
              fulfill-future! (fn [response]
                                (let [context (assoc context :http-response response)]
                                  (.complete cf context)))]
          (a/take! resp-ch fulfill-future!)
          cf))})

(def decode-response-step
  {:name "decode response"
  :f (fn [{:keys [service op-map http-response] :as context}]
       (assoc context :decoded (handle-http-response service op-map http-response)))})

(def add-presigned-query-string-step
  {:name "add presigned query-string"
   :f (fn [{:keys [service endpoint credentials http-request op-map] :as context}]
        (update context
                :http-request
                cognitect.aws.signers/presign-http-request
                (:op op-map)
                (or (:timeout op-map) 60)
                service
                endpoint
                credentials))})

(def default-stack
  [fetch-region-step
   fetch-credentials-step
   discover-endpoint-step
   build-http-request-step
   add-endpoint-step
   body-to-byte-buffer-step
   http-interceptors-step
   sign-request-step
   send-request-step
   decode-response-step])

(def create-presigned-request-stack
  [fetch-region-step
   fetch-credentials-step
   discover-endpoint-step
   build-http-request-step
   add-endpoint-step
   body-to-byte-buffer-step
   http-interceptors-step
   add-presigned-query-string-step])

(defn fetch-presigned-request-stack [http-request]
  [{:name "add http-request"
    :f (fn [context]
         (assoc context :http-request http-request))}
   send-request-step
   decode-response-step])

(defn flow-request
  [client op-map stk]
  (let [executor (java.util.concurrent.ForkJoinPool/commonPool)
        submit! (fn [f]
                  (java.util.concurrent.CompletableFuture/supplyAsync
                   (reify java.util.function.Supplier
                     (get [_] (f)))
                   executor))
        {:keys [service http-client region-provider credentials-provider endpoint-provider]}
        (-get-info client)]
    (flow/execute-future (assoc (-get-info client)
                                :op-map op-map
                                :submit! submit!)
                         (conj
                          stk
                          ;; this should be configurable with a logical default
                          {:name "cleanup"
                           :f #(dissoc %
                                       :op-map
                                       :endpoint
                                       :submit!
                                       :retriable?
                                       :credentials
                                       :region
                                       :service
                                       :backoff
                                       :http-client
                                       :endpoint-provider
                                       :region-provider
                                       :credentials-provider)}))))

(comment
  (require '[cognitect.aws.client.api :as aws])
  (System/setProperty "aws.profile" "REDACTED")

  (def c (aws/client {:api :s3}))

  @(flow-request c {:op :ListBuckets} default-stack)

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; presigned requests
  ;;
  ;; works for ListBuckets
  @(flow-request c {:op :ListBuckets
                    :timeout 30}
                 create-presigned-request-stack)

  @(flow-request c
                 {:op :ListBuckets}
                 (fetch-presigned-request-stack (:http-request *1)))

  (def bucket (-> *1 :decoded :Buckets first :Name))

  ;; works for ListObjects
  @(flow-request c {:op :ListObjects
                    :request {:Bucket bucket}}
                 create-presigned-request-stack)

  @(flow-request c
                 {:op :ListObjects
                  :request {:Bucket bucket}}
                 (fetch-presigned-request-stack (:http-request *1)))

  ;; not so much for ListObjectsV2 because it has its own query string
  @(flow-request c {:op :ListObjectsV2
                    :request {:Bucket bucket}}
                 create-presigned-request-stack)

  @(flow-request c
                 {:op :ListObjectsV2
                  :request {:Bucket bucket}}
                 (fetch-presigned-request-stack (:http-request *1)))


)
