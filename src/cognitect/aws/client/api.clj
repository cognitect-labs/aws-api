;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.client.api
  "API functions for using a client to interact with AWS services."
  (:require [clojure.core.async :as a]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [cognitect.aws.dynaload :as dynaload]
            [cognitect.aws.client :as client]
            [cognitect.aws.retry :as retry]
            [cognitect.aws.client.shared :as shared]
            [cognitect.aws.credentials :as credentials]
            [cognitect.aws.endpoint :as endpoint]
            [cognitect.aws.flow.default-stack :as default-stack]
            [cognitect.aws.http :as http]
            [cognitect.aws.service :as service]
            [cognitect.aws.region :as region]
            [cognitect.aws.client.api.async :as api.async]
            [cognitect.aws.util :as util]))

(declare ops)

(defn client
  "Given a config map, create a client for specified api. Supported keys:

  :api                  - optional, keyword name of the AWS api you want to interact
                          with e.g. :s3, :cloudformation, etc.
                          Must be provided to invoke if not provided here.
  :http-client          - optional, to share http-clients across aws-clients.
                          See default-http-client.
  :region-provider      - optional, implementation of aws-clojure.region/RegionProvider
                          protocol, defaults to cognitect.aws.region/default-region-provider.
                          Ignored if :region is also provided
  :region               - optional, the aws region serving the API endpoints you
                          want to interact with, defaults to region provided by
                          by the region-provider
  :credentials-provider - optional, implementation of
                          cognitect.aws.credentials/CredentialsProvider
                          protocol, defaults to
                          cognitect.aws.credentials/default-credentials-provider
  :endpoint-override    - optional, map to override parts of the endpoint. Supported keys:
                            :protocol     - :http or :https
                            :hostname     - string
                            :port         - int
                            :path         - string
                          If the hostname includes an AWS region, be sure use the same
                          region for the client (either via out of process configuration
                          or the :region key supplied to this fn).
                          Also supports a string representing just the hostname, though
                          support for a string is deprectated and may be removed in the
                          future.
  :retriable?           - optional, fn of http-response (see cognitect.aws.http/submit).
                          Should return a boolean telling the client whether or
                          not the request is retriable.  The default,
                          cognitect.aws.retry/default-retriable?, returns
                          true when the response indicates that the service is
                          busy or unavailable.
  :backoff              - optional, fn of number of retries so far. Should return
                          number of milliseconds to wait before the next retry
                          (if the request is retriable?), or nil if it should stop.
                          Defaults to cognitect.aws.retry/default-backoff.
  :workflow             - optional, keyword indicating execution workflow.
                          Valid values:
                          - :cognitect.aws.alpha.workflow/default (default)
                          - :cognitect.aws.alpha.workflow/presigned-url

  By default, all clients use shared http-client, credentials-provider, and
  region-provider instances which use a small collection of daemon threads.

  Alpha. Subject to change."
  [{:keys [api region region-provider retriable? backoff credentials-provider endpoint endpoint-override
           http-client workflow]
    :or   {endpoint-override {}}}]
  (when (string? endpoint-override)
    (log/warn
     (format
      "DEPRECATION NOTICE: :endpoint-override string is deprecated.\nUse {:endpoint-override {:hostname \"%s\"}} instead."
      endpoint-override)))
  (client/->Client
   (atom {'clojure.core.protocols/datafy (fn [c]
                                           (let [i (client/-get-info c)]
                                             ;; TODO: think about what this means in a world in which
                                             ;; clients could have no api to begin with
                                             (-> {:service  (service/service-description (name api))
                                                  :ops      (ops c)
                                                  :region   (-> i :region-provider region/fetch)
                                                  :endpoint (-> i :endpoint-provider endpoint/fetch)}
                                                 (update :service select-keys [:metadata])
                                                 (update :endpoint select-keys [:hostname :protocols :signatureVersions]))))})
   {:api                  api
    :retriable?           (or retriable? retry/default-retriable?)
    :backoff              (or backoff retry/default-backoff)
    :http-client          http-client
    :endpoint-override    endpoint-override
    :region-provider      (or region-provider
                              (and region
                                   (region/basic-region-provider region)))
    :credentials-provider credentials-provider
    :validate-requests?   (atom nil)
    :workflow             workflow}))

(defn default-http-client
  "Create an http-client to share across multiple aws-api clients."
  []
  (http/resolve-http-client nil))

(defn invoke
  "Package and send a request to AWS and return the result.

  Supported keys in op-map:

  :api                  - required if not provided to client (see client)
  :op                   - required, keyword, the op to perform
  :request              - required only for ops that require them.
  :retriable?           - optional, defaults to :retriable? on the client.
                          See client.
  :backoff              - optional, defaults to :backoff on the client.
                          See client.
  :workflow             - optional, keyword indicating execution workflow.
                          Valid values:
                          - :cognitect.aws.alpha.workflow/default (default)
                          - :cognitect.aws.alpha.workflow/presigned-url

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

(defn ^:private resolve-api-key [client-or-api-key]
  (if (or (string? client-or-api-key)
          (keyword? client-or-api-key))
    client-or-api-key
    (some-> client-or-api-key client/-get-info :api)))

(defn ^:private resolve-service [client-or-api-key]
  (some-> client-or-api-key
          resolve-api-key
          name
          service/service-description))

(defn request-spec-key
  "Returns the key for the request spec for op.

  Alpha. Subject to change."
  [client-or-api-key op]
  (if-let [service (resolve-service client-or-api-key)]
    (service/request-spec-key service op)
    "Client is missing :api key"))

(defn response-spec-key
  "Returns the key for the response spec for op.

  Alpha. Subject to change."
  [client-or-api-key op]
  (if-let [service (resolve-service client-or-api-key)]
    (service/response-spec-key service op)
    "Client is missing :api key"))

(def ^:private pprint-ref (delay (dynaload/load-var 'clojure.pprint/pprint)))
(defn ^:skip-wiki pprint
  "For internal use. Don't call directly."
  [& args]
  (binding [*print-namespace-maps* false]
    (apply @pprint-ref args)))

(defn ops
  "Returns a map of operation name to operation data for this client.

  Alpha. Subject to change."
  [client-or-api-key]
  (if-let [api-key (resolve-api-key client-or-api-key)]
    (service/docs (service/service-description (name api-key)))
    "Client is missing :api key"))

(defn doc-str
  "Given data produced by `ops`, returns a string
  representation.

  Alpha. Subject to change."
  [{:keys [documentation documentationUrl request required response refs] :as doc}]
  (when doc
    (str/join "\n"
              (cond-> ["-------------------------"
                       (:name doc)
                       ""
                       documentation]
                documentationUrl
                (into [""
                       documentationUrl])
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
  [client-or-api-key op]
  (if-let [api-key (resolve-api-key client-or-api-key)]
    (println (or (some-> api-key ops op doc-str)
                 (str "No docs for " (name op) " in " (name api-key))))
    "Client is missing :api key"))

(defn stop
  "Has no effect when the underlying http-client is the shared
  instance.

  If you explicitly provided any other instance of http-client, stops
  it, releasing resources.

  Alpha. Subject to change."
  [aws-client]
  ;; NOTE: (dchelimsky,2020-05-02) getting this via invoke is a bit goofy -
  ;; did this in the transition to execution flow model in order to preserve
  ;; this API.
  (let [http-client (:http-client (invoke aws-client {:workflow-steps [default-stack/add-http-client]}))]
    (when-not (#'shared/shared-http-client? http-client)
      (http/stop http-client))))
