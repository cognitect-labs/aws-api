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
  {:name "add endpoint provider"
   :f (fn [{:keys [api service endpoint-override] :as context}]
        (assoc context :endpoint-provider
               (endpoint/default-endpoint-provider
                api
                (get-in service [:metadata :endpointPrefix])
                endpoint-override)))})

(def fetch-region-step
  {:name "fetch region"
   :f (fn [{:keys [executor region-provider] :as context}]
        (flow/submit executor #(if-let [region (-> (region/fetch region-provider))]
                                 (assoc context :region region)
                                 {:cognitect.anomalies/category :cognitect.anomalies/fault
                                  :cognitect.anomalies/message "Unable to fetch region"})))})

(def fetch-credentials-step
  {:name "fetch credentials"
   :f (fn [{:keys [executor credentials-provider] :as context}]
        (flow/submit executor #(if-let [creds (credentials/fetch credentials-provider)]
                                 (assoc context :credentials creds)
                                 {:cognitect.anomalies/category :cognitect.anomalies/fault
                                  :cognitect.anomalies/message "Unable to fetch credentials"})))})

(def discover-endpoint-step
  {:name "discover endpoint"
   :f (fn [{:keys [executor endpoint-provider region] :as context}]
        (flow/submit executor #(let [endpoint (endpoint/fetch endpoint-provider region)]
                                 (if (:cognitect.anomalies/category endpoint)
                                   endpoint
                                   (assoc context :endpoint endpoint)) )))})

(def build-http-request-step
  {:name "build http request"
   :f (fn [{:keys [service] :as context}]
        (assoc context :http-request (client/build-http-request service context)))})

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
        (let [signed (signing/sign-http-request service endpoint credentials http-request)]
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
        (client/handle-http-response service context http-response))})

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
