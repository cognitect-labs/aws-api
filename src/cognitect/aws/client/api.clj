;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.client.api
  "API functions for using a client to interact with AWS services."
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [cognitect.http-client :as http]
            [cognitect.aws.client :as client]
            [cognitect.aws.credentials :as credentials]
            [cognitect.aws.endpoint :as endpoint]
            [cognitect.aws.service :as service]
            [cognitect.aws.region :as region]
            [cognitect.aws.client.api.async :as api.async]
            [cognitect.aws.util :as util]))

(defn client
  "Given a config map, create a client for specified api. Supported keys
  in config are:
  One of api
   or api-descriptor are required.
  :api                  - this or api-descriptor required, the name of the api
                          you want to interact with e.g. s3, cloudformation, etc
  :api-version          - optional, works in conjunction with :api, the
                          yyyy-mm-dd formatted date string. If you don't provide
                          this, we'll use the latest one available.
  :api-descriptor       - this or api required, a reference to a descriptor
                          file for the desired api; can be any valid input for
                          clojure.java.io/reader (file, uri, url, etc)
  :region               - optional, the aws region serving the API endpoints you
                          want to interact with, defaults to region provided by
                          by the default region provider (see cognitect.aws.region)
  :region-provider      - optional, implementation of aws-clojure.region/RegionProvider
                          protocol, defaults to cognitect.aws.region/default-region-provider
  :retry?               - optional, fn which takes an http-response (see
                          cognitect.http-client/submit) and returns a boolean
                          instructing the client whether or not to retry the
                          request. Defaults to
                          cognitect.aws.client/default-retry.
  :backoff              - optional, fn which takes the number of retries so far
                          and returns a number of milliseconds to wait before
                          the next retry (if the retry? fn indicates more
                          retries), defaults to
                          (cognitect.aws.client/capped-exponential-backoff 300 20000 0)
  :credentials-provider - optional, implementation of
                          cognitect.aws.credentials/CredentialsProvider
                          protocol, defaults to
                          cognitect.aws.credentials/default-credentials-provider"
  [{:keys [api region region-provider retry? backoff credentials-provider] :as config}]
  (let [service (service/service-description (name api))
        region (keyword
                (or region
                    (region/fetch
                     (or region-provider
                         (region/default-region-provider)))))]
    ;; TODO: (dchelimsky 2018-07-19) why is this here instead of the ns dec?
    (require 'cognitect.aws.signers)
    (require (symbol (str "cognitect.aws.protocols." (get-in service [:metadata :protocol]))))
    {:service service
     :region region
     :endpoint (or (endpoint/resolve api region)
                   (throw (ex-info "No known endpoint." {:service api :region region})))
     :retry? (or retry? client/default-retry?)
     :backoff (or backoff (client/capped-exponential-backoff 300 20000 0))
     :http-client (http/create {:trust-all true}) ;; FIX :trust-all
     :credentials (credentials/auto-refreshing-credentials
                   (or credentials-provider
                       (credentials/default-credentials-provider)))}))

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
  (->> client :service :operations keys sort))

(defn request-spec
  "Returns the key for the request spec for op."
  [client op]
  (service/request-spec-key (:service client) op))

(defn response-spec
  "Returns the key for the response spec for op."
  [client op]
  (service/response-spec-key (:service client) op))

(def ^:private pprint-ref (delay (util/dynaload 'clojure.pprint/pprint)))
(defn pprint [& args]
  (binding [*print-namespace-maps* false]
    (apply @pprint-ref args)))

(defn doc
  "Just like clojure.repl/doc for op on client."
  [client op]
  (let [docs (service/docs (:service client))]
    (require (service/spec-ns (:service client)))
    (if-let [op-doc (get docs op)]
      (do
        (println "-------------------------")
        (println (name op))
        (println)
        (println (:documentation op-doc))
        (when-let [request (:request op-doc)]
          (println)
          (println "-------------------------")
          (println "Request Syntax")
          (println)
          (pprint (:main request))
          (when-let [given (:refs request)]
            (println)
            (println "Given")
            (println)
            (pprint given)))
        (when-let [response (:response op-doc)]
          (println)
          (println "-------------------------")
          (println "Response Syntax")
          (println)
          (pprint (:main response))
          (when-let [given (:refs response)]
            (println)
            (println "Given")
            (println)
            (pprint given)))
        (println ""))
      (println "No docs for" (name op)))))
