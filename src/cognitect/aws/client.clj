;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.client
  "Impl, don't call directly."
  (:require [clojure.core.async :as a]
            [cognitect.aws.http :as http]
            [cognitect.aws.util :as util]
            [cognitect.aws.interceptors :as interceptors]
            [cognitect.aws.dynaload :as dynaload]
            [cognitect.aws.endpoint :as endpoint]
            [cognitect.aws.region :as region]
            [cognitect.aws.credentials :as credentials]
            [cognitect.aws.service :as service]
             [cognitect.aws.client.shared :as shared]
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

(defmulti presign-http-request*
  "Presign the HTTP request."
  (fn [context]
    (get-in context [:service :metadata :signatureVersion])))

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

;;; steps
;;;
(def load-service-step
  {:name "load service"
   :f (fn [{:keys [api] :as context}]
        (let [service (service/service-description (name api))]
          (dynaload/load-ns (symbol (str "cognitect.aws.protocols." (get-in service [:metadata :protocol]))))
          (assoc context :service service)))})

(def check-op-step
  {:name "check op"
   :f (fn [{:keys [op service] :as context}]
        (if-not (contains? (:operations service) op)
          {:cognitect.anomalies/category :cognitect.anomalies/incorrect
           :throwable (ex-info "Operation not supported" {:service (keyword (service/service-name service))
                                                          :operation op})}
          context))})

(def add-http-provider-step
  {:name "add http provider"
   :f (fn [context]
        (update context :http-client
                (fn [c]
                  (if c
                    (http/resolve-http-client c)
                    (shared/http-client)))))})

(def add-region-provider-step
  {:name "add region provider"
   :f (fn [{:keys [region region-provider] :as context}]
        (assoc context :region-provider
               (cond
                 region          (reify region/RegionProvider (fetch [_] region))
                 region-provider  region-provider
                 :else           (shared/region-provider))))})

(def add-credentials-provider-step
  {:name "add credentials provider"
   :f (fn [context]
        (if (:credentials-provider context)
          context
          (assoc context :credentials-provider (shared/credentials-provider))))})

(def add-endpoint-provider-step
  {:name "endpoint provider"
   :f (fn [{:keys [api service endpoint-override] :as context}]
        (assoc context :endpoint-provider
               (endpoint/default-endpoint-provider
                api
                (get-in service [:metadata :endpointPrefix])
                endpoint-override)))})

(def fetch-region-step
  {:name "fetch region"
   :f (fn [{:keys [executor region-provider] :as context}]
        (flow/submit executor #(assoc context :region (region/fetch region-provider))))})

(def fetch-credentials-step
  {:name "fetch credentials"
   :f (fn [{:keys [executor credentials-provider] :as context}]
        (flow/submit executor #(assoc context :credentials (credentials/fetch credentials-provider))))})

(def discover-endpoint-step
  {:name "discover endpoint"
   :f (fn [{:keys [executor endpoint-provider region] :as context}]
        (flow/submit executor #(assoc context :endpoint (endpoint/fetch endpoint-provider region))))})

(def build-http-request-step
  {:name "build http request"
   :f (fn [{:keys [service] :as context}]
        (assoc context :http-request (build-http-request service context)))})

(def add-endpoint-step
  {:name "add endpoint"
   :f (fn [{:keys [endpoint] :as context}]
        (update context :http-request with-endpoint endpoint))})

(def body-to-byte-buffer-step
  {:name "body to ByteBuffer"
   :f #(update-in % [:http-request :body] util/->bbuf)})

(def http-interceptors-step
  {:name "http interceptors"
   :f (fn [{:keys [service] :as context}]
        (update context :http-request
                (fn [r]
                  (interceptors/modify-http-request service context r))))})

(def sign-request-step
  {:name "sign request"
   :f (fn [{:keys [service endpoint credentials http-request] :as  context}]
        (let [signed (sign-http-request service endpoint credentials http-request)]
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
   :f (fn [{:keys [service http-response] :as context}]
        (handle-http-response service context http-response))})

(def add-presigned-query-string-step
  {:name "add presigned query-string"
   :f (fn [{:keys [op service endpoint credentials http-request] :as context}]
        (assoc context :http-request (presign-http-request* context)))})

(def default-stack
  [load-service-step
   check-op-step
   add-http-provider-step
   add-region-provider-step
   add-credentials-provider-step
   add-endpoint-provider-step

   fetch-region-step
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
  [load-service-step
   check-op-step
   add-http-provider-step
   add-region-provider-step
   add-credentials-provider-step
   add-endpoint-provider-step

   fetch-region-step
   fetch-credentials-step
   discover-endpoint-step
   build-http-request-step
   add-endpoint-step
   body-to-byte-buffer-step
   http-interceptors-step
   add-presigned-query-string-step])

(def exec-presigned-request-stack
  [send-request-step
   decode-response-step])

(defn flow-request
  ([request stk]
   (let [request (assoc request :executor (java.util.concurrent.ForkJoinPool/commonPool))]
     (flow/execute-future request stk)))
  ([client-info request stk]
   (flow-request (merge client-info request) stk)))

(comment
  (System/setProperty "aws.profile" "aws-api-test")
  (set! *print-level* 10)

  (def c {:api :s3})
  (require 'cognitect.aws.signers)

  (defn remove-service [node]
    (if (:service node)
      (dissoc node :service)
      node))

  (defn summarize-log
    [resp]
    (mapv #(select-keys % [:name :ms]) (::flow/log resp)))

  (-> @(flow-request c {:op :ListBuckets} default-stack)
      (dissoc ::flow/log))

  (def bucket (-> *1 :Buckets first :Name))

  (-> @(flow-request c {:op :ListObjects
                        :request {:Bucket bucket}
                        :timeout 30}
                     default-stack)
      (dissoc ::flow/log))

  (-> @(flow-request c {:op :ListObjectsV2
                        :request {:Bucket bucket}
                        :timeout 30}
                     default-stack)
      (update ::flow/log
              #(clojure.walk/postwalk remove-service %)))

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; presigned requests
  ;;
  ;; ListBuckets
  (def presigned @(flow-request c {:op :ListBuckets
                                   :timeout 30}
                                create-presigned-request-stack))

  (-> @(flow/execute-future presigned exec-presigned-request-stack)
      ::flow/log)

  (def bucket (-> *1 :Buckets first :Name))

  ;; ListObjects

  (def presigned @(flow-request c {:op :ListObjects
                                   :request {:Bucket bucket}
                                   :timeout 30}
                                create-presigned-request-stack))

  (-> @(flow/execute-future presigned exec-presigned-request-stack)
      (dissoc ::flow/log))

  ;; ListObjectsV2

  (def presigned @(flow-request c {:op :ListObjectsV2
                                   :request {:Bucket bucket}
                                   :timeout 30}
                                create-presigned-request-stack))

  (-> @(flow/execute-future presigned exec-presigned-request-stack)
      (dissoc ::flow/log))

)
