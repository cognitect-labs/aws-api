;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.client.api
  "API functions for using a client to interact with AWS services."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [cognitect.http-client :as http]
            [cognitect.aws.client :as client]
            [cognitect.aws.retry :as retry]
            [cognitect.aws.credentials :as credentials]
            [cognitect.aws.endpoint :as endpoint]
            [cognitect.aws.service :as service]
            [cognitect.aws.region :as region]
            [cognitect.aws.client.api.async :as api.async]
            [cognitect.aws.signers] ;; implements multimethods
            [cognitect.aws.util :as util]))

(declare ops)

(defn client
  "Given a config map, create a client for specified api. Supported keys
  in config are:
  :api                  - required, this or api-descriptor required, the name of the api
                          you want to interact with e.g. :s3, :cloudformation, etc
  :region               - optional, the aws region serving the API endpoints you
                          want to interact with, defaults to region provided by
                          by the default region provider (see cognitect.aws.region)
  :credentials-provider - optional, implementation of
                          cognitect.aws.credentials/CredentialsProvider
                          protocol, defaults to
                          cognitect.aws.credentials/default-credentials-provider
  :endpoint-override    - optional, overrides the configured endpoint. If the endpoint
                          includes an AWS region, be sure use the same region for
                          the client (either via out of process configuration or the :region key
                          passed to this fn).
  :region-provider      - optional, implementation of aws-clojure.region/RegionProvider
                          protocol, defaults to cognitect.aws.region/default-region-provider
  :retriable?           - optional, fn of http-response (see cognitect.http-client/submit).
                          Should return a boolean telling the client whether or
                          not the request is retriable.  The default,
                          cognitect.aws.retry/default-retriable?, returns
                          true when the response indicates that the service is
                          busy or unavailable.
  :backoff              - optional, fn of number of retries so far. Should return
                          number of milliseconds to wait before the next retry
                          (if the request is retriable?), or nil if it should stop.
                          Defaults to cognitect.aws.retry/default-backoff.

  Alpha. Subject to change."
  [{:keys [api region region-provider retriable? backoff credentials-provider endpoint-override] :as config}]
  (let [service (service/service-description (name api))
        region  (keyword
                 (or region
                     (region/fetch
                      (or region-provider
                          (region/default-region-provider)))))]
    (require (symbol (str "cognitect.aws.protocols." (get-in service [:metadata :protocol]))))
    (with-meta
      (client/->Client
       (atom {})
       {:service     service
        :region      region
        :endpoint    (or (cond-> (endpoint/resolve (-> service :metadata :endpointPrefix keyword) region)
                           endpoint-override
                           (assoc :hostname endpoint-override))
                         (throw (ex-info "No known endpoint." {:service api :region region})))
        :retriable?  (or retriable? retry/default-retriable?)
        :backoff     (or backoff retry/default-backoff)
        :http-client (http/create {:trust-all true}) ;; FIX :trust-all
        :credentials (or credentials-provider @credentials/global-provider)})
      {'clojure.core.protocols/datafy (fn [c]
                                        (-> c
                                            client/-get-info
                                            (select-keys [:region :endpoint :service])
                                            (update :endpoint select-keys [:hostname :protocols :signatureVersions])
                                            (update :service select-keys [:metadata])
                                            (assoc :ops (ops c))))})))

(defn invoke
  "Package and send a request to AWS and return the result.

  Supported keys in op-map:

  :op                   - required, keyword, the op to perform
  :request              - required only for ops that require them.
  :retriable?           - optional, defaults to :retriable? on the client.
                          See client.
  :backoff              - optional, defaults to :backoff on the client.
                          See client.

  After invoking (cognitect.aws.client.api/validate-requests true), validates
  :request in op-map.

  Alpha. Subject to change."
  [client op-map]
  (a/<!! (api.async/invoke client op-map)))

(defn validate-requests
  "Given true, uses clojure.spec to validate all invoke calls on client.

  Alpha. Subject to change."
  ([client]
   (validate-requests client true))
  ([client bool]
   (api.async/validate-requests client bool)))

(defn request-spec-key
  "Returns the key for the request spec for op.

  Alpha. Subject to change."
  [client op]
  (service/request-spec-key (-> client client/-get-info :service) op))

(defn response-spec-key
  "Returns the key for the response spec for op.

  Alpha. Subject to change."
  [client op]
  (service/response-spec-key (-> client client/-get-info :service) op))

(def ^:private pprint-ref (delay (util/dynaload 'clojure.pprint/pprint)))
(defn ^:skip-wiki pprint
  "For internal use. Don't call directly."
  [& args]
  (binding [*print-namespace-maps* false]
    (apply @pprint-ref args)))

(defn ops
  "Returns a map of operation name to operation data for this client.

  Alpha. Subject to change."
  [client]
  (->> client
       client/-get-info
       :service
       service/docs))

(defn doc-str
  "Given data produced by `ops`, returns a string
  representation.

  Alpha. Subject to change."
  [{:keys [documentation request required response refs] :as doc}]
  (when doc
    (str/join "\n"
              (cond-> ["-------------------------"
                       (:name doc)
                       ""
                       documentation]
                request
                (into [""
                       "-------------------------"
                       "Request"
                       ""
                       (with-out-str (pprint request))])
                required
                (into ["Required"
                       ""
                       (with-out-str (pprint required))])
                response
                (into ["-------------------------"
                       "Response"
                       ""
                       (with-out-str (pprint response))])
                refs
                (into ["-------------------------"
                       "Given"
                       ""
                       (with-out-str (pprint refs))])))))

(defn doc
  "Given a client and an operation (keyword), prints documentation
  for that operation to the current value of *out*. Returns nil.

  Alpha. Subject to change."
  [client operation]
  (println (or (some-> client ops operation doc-str)
               (str "No docs for " (name operation)))))

(defn stop
  "Shuts down the http-client, releasing resources.

  Alpha. Subject to change."
  [client]
  (let [{:keys [http-client credentials]} (client/-get-info client)]
    (http/stop http-client)))
