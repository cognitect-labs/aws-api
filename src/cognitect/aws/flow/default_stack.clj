;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.flow.default-stack
  "Impl, don't call directly."
  (:require [clojure.core.async :as a]
            [cognitect.aws.client :as client]
            [cognitect.aws.http :as http]
            [cognitect.aws.util :as util]
            [cognitect.aws.interceptors :as interceptors]
            [cognitect.aws.dynaload :as dynaload]
            [cognitect.aws.endpoint :as endpoint]
            [cognitect.aws.region :as region]
            [cognitect.aws.credentials :as credentials]
            [cognitect.aws.service :as service]
            [cognitect.aws.signing :as signing]
            [cognitect.aws.signing.impl] ;; implements multimethods
            [cognitect.aws.client.shared :as shared]
            [cognitect.aws.flow :as flow]))

(set! *warn-on-reflection* true)

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

(def load-service
  {:name "load service"
   :f (fn [{:keys [api] :as context}]
        (let [service (service/service-description (name api))]
          (dynaload/load-ns (symbol (str "cognitect.aws.protocols." (get-in service [:metadata :protocol]))))
          (assoc context :service service)))})

(def check-op
  {:name "check op"
   :f (fn [{:keys [op service] :as context}]
        (if-not (contains? (:operations service) op)
          {:cognitect.anomalies/category :cognitect.anomalies/incorrect
           :throwable (ex-info "Operation not supported" {:service (keyword (service/service-name service))
                                                          :operation op})}
          context))})

(def add-http-client
  {:name "add http provider"
   :f (fn [context]
        (update context :http-client
                (fn [c]
                  (if c
                    (http/resolve-http-client c)
                    (shared/http-client)))))})

(def add-region-provider
  {:name "add region provider"
   :f (fn [{:keys [region region-provider] :as context}]
        (assoc context :region-provider
               (cond
                 region          (reify region/RegionProvider (fetch [_] region))
                 region-provider  region-provider
                 :else           (shared/region-provider))))})

(def add-credentials-provider
  {:name "add credentials provider"
   :f (fn [context]
        (if (:credentials-provider context)
          context
          (assoc context :credentials-provider (shared/credentials-provider))))})

(def add-endpoint-provider
  {:name "add endpoint provider"
   :f (fn [{:keys [api service endpoint-override] :as context}]
        (assoc context :endpoint-provider
               (endpoint/default-endpoint-provider
                api
                (get-in service [:metadata :endpointPrefix])
                endpoint-override)))})

(def provide-region
  {:name "provide region"
   :f (fn [{:keys [executor region-provider] :as context}]
        (flow/submit executor #(if-let [region (-> (region/fetch region-provider))]
                                 (assoc context :region region)
                                 {:cognitect.anomalies/category :cognitect.anomalies/fault
                                  :cognitect.anomalies/message "Unable to fetch region"})))})

(def provide-credentials
  {:name "provide credentials"
   :f (fn [{:keys [executor credentials-provider] :as context}]
        (flow/submit executor #(if-let [creds (credentials/fetch credentials-provider)]
                                 (assoc context :credentials creds)
                                 {:cognitect.anomalies/category :cognitect.anomalies/fault
                                  :cognitect.anomalies/message "Unable to fetch credentials"})))})

(def provide-endpoint
  {:name "provide endpoint"
   :f (fn [{:keys [executor endpoint-provider region] :as context}]
        (flow/submit executor #(let [endpoint (endpoint/fetch endpoint-provider region)]
                                 (if (:cognitect.anomalies/category endpoint)
                                   endpoint
                                   (assoc context :endpoint endpoint)) )))})

(def build-http-request
  {:name "build http request"
   :f (fn [{:keys [service] :as context}]
        (assoc context :http-request (client/build-http-request service context)))})

(def apply-endpoint
  {:name "apply endpoint"
   :f (fn [{:keys [endpoint] :as context}]
        (update context :http-request with-endpoint endpoint))})

(def body-to-byte-buffer
  {:name "body to ByteBuffer"
   :f #(update-in % [:http-request :body] util/->bbuf)})

(def http-interceptors
  {:name "http interceptors"
   :f (fn [{:keys [service] :as context}]
        (update context :http-request
                (fn [r]
                  (interceptors/modify-http-request service context r))))})

(def sign-request
  {:name "sign request"
   :f (fn [{:keys [service endpoint credentials http-request] :as  context}]
        (let [signed (signing/sign-http-request service endpoint credentials http-request)]
          (assoc context :http-request signed)))})

(def send-request
  {:name "send request"
   :f (fn [{:keys [http-client http-request] :as context}]
        (let [cf (java.util.concurrent.CompletableFuture.)
              resp-ch (http/submit http-client http-request)
              fulfill-future! (fn [response]
                                (let [context (assoc context :http-response response)]
                                  (.complete cf context)))]
          (a/take! resp-ch fulfill-future!)
          cf))})

(def decode-response
  {:name "decode response"
   :f (fn [{:keys [service http-response] :as context}]
        (client/handle-http-response service context http-response))})

(def default-stack
  [load-service              ;; resolution
   check-op                  ;; validation
   add-http-client           ;; resolution
   add-region-provider       ;; resolution
   add-credentials-provider  ;; resolution
   add-endpoint-provider     ;; resolution
   provide-region            ;; resolution
   provide-credentials       ;; resolution
   provide-endpoint          ;; resolution

   build-http-request        ;; process
   apply-endpoint              ;; process / modification
   body-to-byte-buffer       ;; process / modification
   http-interceptors         ;; process / modification
   sign-request              ;; process / modification

   send-request              ;; action
   decode-response           ;; post-process
   ])
