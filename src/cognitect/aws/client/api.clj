;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.client.api
  "API functions for using a client to interact with AWS services."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.walk :as walk]
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

(deftype Client [info]
  client/ClientSPI
  (-get-info [_] info))

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
                          Defaults to cognitect.aws.retry/default-backoff."
  [{:keys [api region region-provider retriable? backoff credentials-provider] :as config}]
  (let [service (service/service-description (name api))
        region  (keyword
                 (or region
                     (region/fetch
                      (or region-provider
                          (region/default-region-provider)))))]
    (require (symbol (str "cognitect.aws.protocols." (get-in service [:metadata :protocol]))))
    (->Client
     {:service     service
      :region      region
      :endpoint    (or (endpoint/resolve (-> service :metadata :endpointPrefix keyword) region)
                       (throw (ex-info "No known endpoint." {:service api :region region})))
      :retriable?  (or retriable? retry/default-retriable?)
      :backoff     (or backoff retry/default-backoff)
      :http-client (http/create {:trust-all true}) ;; FIX :trust-all
      :credentials (credentials/auto-refreshing-credentials
                    (or credentials-provider
                        (credentials/default-credentials-provider)))})))

(defn invoke
  "Package and send a request to AWS and return the result.
  Supported keys in op-map:

  :op                   - required, keyword, the op to perform
  :request              - required only for ops that require them
  :retriable?           - optional, defaults to :retriable? on the client.
                          See client.
  :backoff              - optional, defaults to :backoff on the client.
                          See client.

  If (cognitect.aws.client.api/validate-requests) is true, validates
  :request in op-map."
  [client op-map]
  (a/<!! (api.async/invoke client op-map)))

(defn validate-requests
  ([client]
   (validate-requests client true))
  ([client bool]
   (api.async/validate-requests client bool)))

(defn request-spec-key
  "Returns the key for the request spec for op."
  [client op]
  (service/request-spec-key (-> client client/-get-info :service) op))

(defn response-spec-key
  "Returns the key for the response spec for op."
  [client op]
  (service/response-spec-key (-> client client/-get-info :service) op))

(def ^:private pprint-ref (delay (util/dynaload 'clojure.pprint/pprint)))
(defn pprint [& args]
  (binding [*print-namespace-maps* false]
    (apply @pprint-ref args)))

(defn ops-data [client]
  (->> client
       client/-get-info
       :service
       service/docs))

(defn doc-str
  "Given data produced by `ops-data` (or similar), returns a string
  representation."
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
  for that operation to the current value of *out*. Returns nil."
  [client operation]
  (println (or (some-> (ops-data client) operation doc-str)
               (str "No docs for " (name operation)))))
