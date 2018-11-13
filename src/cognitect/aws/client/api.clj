;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.client.api
  "API functions for using a client to interact with AWS services."
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cognitect.http-client :as http]
            [cognitect.aws.client :as client]
            [cognitect.aws.credentials :as credentials]
            [cognitect.aws.endpoint :as endpoint]
            [cognitect.aws.service :as service]
            [cognitect.aws.region :as region]
            [cognitect.aws.client.api.async :as api.async]
            [cognitect.aws.signers]
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
  :retry?               - optional, fn of http-response (see cognitect.http-client/submit).
                          Returns a boolean instructing the client whether or
                          not to retry the request. Default: cognitect.aws.client/default-retry.
  :backoff              - optional, fn of number of retries so far. Should return
                          number of milliseconds to wait before the next retry
                          (if the retry? fn returns true. Default:
                          (cognitect.aws.client/capped-exponential-backoff 300 20000 0)
  "
  [{:keys [api region region-provider retry? backoff credentials-provider] :as config}]
  (let [service (service/service-description (name api))
        region (keyword
                (or region
                    (region/fetch
                     (or region-provider
                         (region/default-region-provider)))))]
    (require (symbol (str "cognitect.aws.protocols." (get-in service [:metadata :protocol]))))
    (->Client
     {:service service
      :region region
      :endpoint (or (endpoint/resolve api region)
                    (throw (ex-info "No known endpoint." {:service api :region region})))
      :retry? (or retry? client/default-retry?)
      :backoff (or backoff (client/capped-exponential-backoff 300 20000 0))
      :http-client (http/create {:trust-all true}) ;; FIX :trust-all
      :credentials (credentials/auto-refreshing-credentials
                    (or credentials-provider
                        (credentials/default-credentials-provider)))})))

(defn validate-requests [client tf]
  (api.async/validate-requests client tf))

(defn invoke
  "Package and send a request to AWS and return the result.
  Supported keys in op-map:

  :op                   - required, keyword, the op to perform
  :request              - required only for ops that require them
  :retry?               - optional, defaults to :retry? passed to client,
                          if present, then cognitect.aws.client/default-retry.
  :backoff              - optional, defaults to :backoff passed to client,
                          if present, then
                          (cognitect.aws.client/capped-exponential-backoff 300 20000 0)

  If (cognitect.aws.client.api/validate-requests) is true, validates
  that :request in op-map is valid for this op."
  [client op-map]
  (a/<!! (api.async/invoke client op-map)))

(defn ops
  "Retuns a list of the operations supported by client."
  [client]
  (->> client client/-get-info :service :operations keys sort))

(defn request-spec
  "Returns the key for the request spec for op."
  [client op]
  (service/request-spec-key (-> client client/-get-info :service) op))

(defn response-spec
  "Returns the key for the response spec for op."
  [client op]
  (service/response-spec-key (-> client client/-get-info :service) op))

(def ^:private pprint-ref (delay (util/dynaload 'clojure.pprint/pprint)))
(defn pprint [& args]
  (binding [*print-namespace-maps* false]
    (apply @pprint-ref args)))

(defn doc-data
  "Given a client and an operation (keyword), returns a map with
  the following keys:
    :operation (same as the one you passed)
    :documentation (from source api description)
    :request (request syntax helper generated from source api description)
    :request (response syntax helper generated from source api description)
  "
  [client operation]
  (let [docs (service/docs (-> client client/-get-info :service))]
    (require (service/spec-ns (-> client client/-get-info :service)))
    (some-> (get docs operation)
            (assoc :operation operation))))

(defn doc-str
  "Given data produced by `doc-data` (or similar), returns a string
  representation."
  [{:keys [operation documentation request response] :as data}]
  (when data
    (str/join "\n"
              (cond-> ["-------------------------"
                       (name operation)
                       ""
                       documentation]
                request
                (into (cond-> [""
                               "-------------------------"
                               "Request Syntax"
                               ""
                               (with-out-str (pprint (:main request)))]
                        (:required request)
                        (into ["Required"
                               ""
                               (with-out-str (pprint (:required request)))])
                        (:refs request)
                        (into ["Given"
                               ""
                               (with-out-str (pprint (:refs request)))])))
                response
                (into (cond-> ["-------------------------"
                               "Response Syntax"
                               ""
                               (with-out-str (pprint (:main response)))]
                        (:refs response)
                        (into ["Given"
                               ""
                               (with-out-str (pprint (:refs response)))])))))))

(defn doc
  "Given a client and an operation (keyword), prints documentation
  for that operation to the current value of *out*. Returns nil."
  [client operation]
  (println (or (some-> (doc-data client operation) doc-str)
               (str "No docs for " (name operation)))))
