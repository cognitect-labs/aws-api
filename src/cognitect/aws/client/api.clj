;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.client.api
  "API functions for using a client to interact with AWS services."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [cognitect.aws.client.impl :as client]
            [cognitect.aws.client.protocol :as client.protocol]
            [cognitect.aws.client.shared :as shared]
            [cognitect.aws.credentials]
            [cognitect.aws.dynaload :as dynaload]
            [cognitect.aws.endpoint :as endpoint]
            [cognitect.aws.http :as http]
            [cognitect.aws.region :as region]
            [cognitect.aws.retry :as retry]
            [cognitect.aws.service :as service]
            [cognitect.aws.signers]))

(set! *warn-on-reflection* true)

(declare ops)

(defn client
  "Given a config map, create a client for specified api. Supported keys:

  :api                  - required, name of the api you want to interact with e.g. s3, cloudformation, etc
  :http-client          - optional, to share http-clients across aws-clients
                          Default: cognitect.aws.client.shared/http-client
  :region-provider      - optional, implementation of aws-clojure.region/RegionProvider
                          protocol, defaults to cognitect.aws.client.shared/region-provider.
                          Ignored if :region is also provided
  :region               - optional, the aws region serving the API endpoints you
                          want to interact with, defaults to region provided by
                          by the region-provider
  :credentials-provider - optional, implementation of
                          cognitect.aws.credentials/CredentialsProvider protocol
                          Default: cognitect.aws.client.shared/credentials-provider
  :endpoint-override    - optional, map to override parts of the endpoint. Supported keys:
                            :protocol     - :http or :https
                            :hostname     - string
                            :port         - int
                            :path         - string
                          If the hostname includes an AWS region, be sure to use the same
                          region for the client (either via out of process configuration
                          or the :region key supplied to this fn).
                          Also supports a string representing just the hostname, though
                          support for a string is deprecated and may be removed in the
                          future.
  :retriable?           - optional, predicate fn of http-response (see cognitect.aws.http/submit),
                          which should return a truthy value if the request is
                          retriable.
                          Default: cognitect.aws.retry/default-retriable?
  :backoff              - optional, fn of number of retries so far. Should return
                          number of milliseconds to wait before the next retry
                          (if the request is retriable?), or nil if it should stop.
                          Default: cognitect.aws.retry/default-backoff.

  By default, all clients use shared http-client, credentials-provider, and
  region-provider instances which use a small collection of daemon threads.

  Primarily for debugging, clients support keyword access for :api (String), :region, :endpoint,
  :credentials, :service (with :metadata), and :http-client.

  Alpha. Subject to change."
  [{:keys [api region region-provider retriable? backoff credentials-provider endpoint-override http-client]
    :or   {endpoint-override {} credentials-provider (shared/credentials-provider)}}]
  (when (string? endpoint-override)
    (log/warn
     (format
      "DEPRECATION NOTICE: :endpoint-override string is deprecated.\nUse {:endpoint-override {:hostname \"%s\"}} instead."
      endpoint-override)))
  (let [service              (service/service-description (name api))
        http-client          (if http-client
                               (http/resolve-http-client http-client)
                               (shared/http-client))
        region-provider      (cond region          (reify region/RegionProvider (fetch [_] region))
                                   region-provider region-provider
                                   :else           (shared/region-provider))
        endpoint-provider    (endpoint/default-endpoint-provider
                              (get-in service [:metadata :endpointPrefix])
                              endpoint-override)]
    (dynaload/load-ns (symbol (str "cognitect.aws.protocols." (get-in service [:metadata :protocol]))))
    (client/->Client
     (atom {'clojure.core.protocols/datafy (fn [c]
                                             (let [info (client.protocol/-get-info c)
                                                   region (region/fetch (:region-provider info))
                                                   endpoint (endpoint/fetch (:endpoint-provider info) region)]
                                               (-> info
                                                   (select-keys [:service])
                                                   (assoc :api (-> info :service :metadata :cognitect.aws/service-name))
                                                   (assoc :region region :endpoint endpoint)
                                                   (update :endpoint select-keys [:hostname :protocols :signatureVersions])
                                                   (update :service select-keys [:metadata])
                                                   (assoc :ops (ops c)))))})
     {:service              service
      :retriable?           (or retriable? retry/default-retriable?)
      :backoff              (or backoff retry/default-backoff)
      :http-client          http-client
      :endpoint-provider    endpoint-provider
      :region-provider      region-provider
      :credentials-provider credentials-provider
      :validate-requests?   (atom nil)})))

(defn default-http-client
  "Create an http-client to share across multiple aws-api clients."
  []
  (http/resolve-http-client nil))

(defn invoke
  "Packages and sends a request to AWS and returns the result.

  Supported keys in op-map:

  :op                   - required, keyword, the op to perform
  :request              - required only for ops that require them.
  :retriable?           - optional, defaults to :retriable? on the client.
                          See client.
  :backoff              - optional, defaults to :backoff on the client.
                          See client.

  See https://github.com/cognitect-labs/aws-api/blob/main/doc/types.md for a
  mapping of AWS types to Clojure/Java types.

  Will validate :request after calling (validate-requests client true).

  Alpha. Subject to change."
  [client op-map]
  (client.protocol/-invoke client op-map))

(defn invoke-async
  "Packages and sends a request to AWS and returns a channel which
  will contain the result.

  Supported keys in op-map:

  :ch                   - optional, channel to deliver the result
  :op                   - required, keyword, the op to perform
  :request              - required only for ops that require them.
  :retriable?           - optional, defaults to :retriable? on the client.
                          See client.
  :backoff              - optional, defaults to :backoff on the client.
                          See client.

  See https://github.com/cognitect-labs/aws-api/blob/main/doc/types.md for a
  mapping of AWS types to Clojure/Java types.

  Will validate :request after calling (validate-requests client true).

  Alpha. Subject to change."
  [client op-map]
  (client.protocol/-invoke-async client op-map))

(defn validate-requests
  "Given true, uses clojure.spec to validate all invoke calls on client.

  Alpha. Subject to change."
  ([client]
   (validate-requests client true))
  ([client validate-requests?]
   (reset! (-> client client.protocol/-get-info :validate-requests?) validate-requests?)
   (when validate-requests?
     (service/load-specs (-> client client.protocol/-get-info :service)))
   validate-requests?))

(defn request-spec-key
  "Returns the key for the request spec for op.

  Alpha. Subject to change."
  [client op]
  (service/request-spec-key (-> client client.protocol/-get-info :service) op))

(defn response-spec-key
  "Returns the key for the response spec for op.

  Alpha. Subject to change."
  [client op]
  (service/response-spec-key (-> client client.protocol/-get-info :service) op))

(def ^:private pprint-ref (delay (dynaload/load-var 'clojure.pprint/pprint)))
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
       client.protocol/-get-info
       :service
       service/docs))

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
   
  See https://github.com/cognitect-labs/aws-api/blob/main/doc/types.md for a
  mapping of AWS types to Clojure/java types.

  Alpha. Subject to change."
  [client operation]
  (println (or (some-> client ops operation doc-str)
               (str "No docs for " (name operation)))))

(defn stop
  "Has no effect when the underlying http-client is the shared
  instance.

  If you explicitly provided any other instance of http-client, stops
  it, releasing resources.

  Alpha. Subject to change."
  [aws-client]
  (client.protocol/-stop aws-client))
