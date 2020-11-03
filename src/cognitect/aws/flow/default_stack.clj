;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.flow.default-stack
  "Impl, don't call directly."
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as gen]
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
            [cognitect.aws.flow :as flow]
            [cognitect.aws.flow.util :refer [defstep]]
            [cognitect.aws.flow.credentials-stack :as credentials-stack]))

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

(defstep load-service [{:keys [api] :as context}]
  (let [service (service/service-description (name api))]
    (dynaload/load-ns (symbol (str "cognitect.aws.protocols." (get-in service [:metadata :protocol]))))
    (assoc context :service service)))

(defstep check-op [{:keys [op service] :as context}]
  (if-not (contains? (:operations service) op)
    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
     :throwable (ex-info "Operation not supported" {:service (keyword (service/service-name service))
                                                    :operation op})}
    context))

(defstep add-http-client [context]
  (update context :http-client
          (fn [c]
            (if c
              (http/resolve-http-client c)
              (shared/http-client)))))

(defstep add-region-provider [{:keys [region-provider] :as context}]
  (if (:region-provider context)
    context
    (assoc context :region-provider (shared/region-provider))))

(defstep provide-region [{:keys [executor region region-provider] :as context}]
  (if region
    context
    (flow/submit executor #(if-let [region (-> (region/fetch region-provider))]
                             (assoc context :region region)
                             {:cognitect.anomalies/category :cognitect.anomalies/fault
                              :cognitect.anomalies/message "Unable to fetch region"}))))

(defstep add-endpoint-provider [{:keys [api service endpoint-override] :as context}]
  (assoc context :endpoint-provider
         (endpoint/default-endpoint-provider
          api
          (get-in service [:metadata :endpointPrefix])
          endpoint-override)))

(defstep provide-endpoint [{:keys [executor endpoint-provider region] :as context}]
  (flow/submit executor #(let [endpoint (endpoint/fetch endpoint-provider region)]
                           (if (:cognitect.anomalies/category endpoint)
                             endpoint
                             (assoc context :endpoint endpoint)) )))

(defstep build-http-request [{:keys [service] :as context}]
  (assoc context :http-request (client/build-http-request service context)))

(defstep apply-endpoint [{:keys [endpoint] :as context}]
  (update context :http-request with-endpoint endpoint))

(defstep body-to-byte-buffer [context] (update-in context [:http-request :body] util/->bbuf))

(defstep http-interceptors [{:keys [service] :as context}]
  (update context :http-request
          (fn [r]
            (interceptors/modify-http-request service context r))))

(defstep sign-request [{:keys [service endpoint credentials http-request] :as  context}]
  (let [signed (signing/sign-http-request service endpoint credentials http-request)]
    (assoc context
           :cognitect.aws.signing/basis (meta signed)
           :http-request signed)))

(defstep send-request [{:keys [http-client http-request] :as context}]
  (let [cf (java.util.concurrent.CompletableFuture.)
        resp-ch (http/submit http-client http-request)
        fulfill-future! (fn [response]
                          (let [context (assoc context :http-response response)]
                            (.complete cf context)))]
    (a/take! resp-ch fulfill-future!)
    cf))

(defstep decode-response [{:keys [service http-response] :as context}]
  (client/handle-http-response service context http-response))

(def default-stack
  [load-service              ;; resolution
   check-op                  ;; validation
   add-http-client           ;; resolution

   add-region-provider       ;; resolution
   provide-region            ;; resolution
   (credentials-stack/process-credentials)
   add-endpoint-provider     ;; resolution
   provide-endpoint          ;; resolution

   build-http-request        ;; process
   apply-endpoint            ;; process / modification
   body-to-byte-buffer       ;; process / modification
   http-interceptors         ;; process / modification
   sign-request              ;; process / modification

   send-request              ;; action
   decode-response           ;; post-process
   ])

(defstep test-send-request [context]
  (let [{:keys [api op test-handler]} context
        service (service/service-description (name api))
        response-spec (or (service/response-spec-key service op) (s/spec any?))
        default-handler (fn [request] (when response-spec
                                        (gen/generate (s/gen response-spec))))]
    (if test-handler
      (let [response (test-handler default-handler context)]
        (if-let [error (s/explain-data response-spec response)]
          (throw (ex-info "test response did not conform" error))
          response))
      (default-handler context))))

(def test-stack
  [load-service              ;; resolution
   check-op                  ;; validation
   add-http-client           ;; resolution

   add-region-provider       ;; resolution
   provide-region            ;; resolution
   (credentials-stack/process-credentials)
   add-endpoint-provider     ;; resolution
   provide-endpoint          ;; resolution

   build-http-request        ;; process
   apply-endpoint            ;; process / modification
   body-to-byte-buffer       ;; process / modification
   http-interceptors         ;; process / modification
   sign-request              ;; process / modification

   test-send-request])
