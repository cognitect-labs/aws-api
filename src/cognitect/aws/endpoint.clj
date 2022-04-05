;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.endpoint
  "Impl, don't call directly."
  (:refer-clojure :exclude [resolve])
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [cognitect.aws.service :as service]))

(set! *warn-on-reflection* true)

(defn descriptor-resource-path [] (format "%s/endpoints.edn" service/base-resource-path))

(defn read-endpoints-description []
  (if-let [resource (io/resource (descriptor-resource-path))]
    (edn/read-string (slurp resource))
    (throw (ex-info (str "Cannot find resource " (descriptor-resource-path) ".") {}))))

(defn resolver
  "Create a new endpoint resolver."
  []
  (read-endpoints-description))

(defn render-uri
  "Given a template, e.g. \"{a}.{b}.{c}\", and a map of replacements
  with keys matching those in the template, replaces {a} with the
  value bound to :a in replacements, then {b}, then {c}."
  [replacements template]
  (str/replace template
               #"\{([^}]+)\}"
               #(get replacements (second %))))

(defn service-resolve
  "Resolve the endpoint for the given service."
  [partition service-name service region-key]
  (let [endpoint  (get-in service [:endpoints region-key])
        region    (name region-key)
        result    (merge (:defaults partition)
                         (:defaults service)
                         endpoint
                         {:partition (:partition partition)
                          :region    region
                          :dnsSuffix (:dnsSuffix partition)})
        uri-parts {"service"   service-name
                   "region"    region
                   "dnsSuffix" (:dnsSuffix partition)}]
    (cond-> result
      (:hostname result)
      (update :hostname (partial render-uri uri-parts))

      (:sslCommonName result)
      (update :sslCommonName (partial render-uri uri-parts)))))

(defn partition-resolve
  [{:keys [services] :as partition} service-key region-key]
  (when (contains? (-> partition :regions keys set) region-key)
    (let [{:keys [partitionEndpoint isRegionalized] :as service} (get services service-key)
          endpoint-key (if (and partitionEndpoint (not isRegionalized))
                         (keyword partitionEndpoint)
                         region-key)]
      (service-resolve partition (name service-key) service endpoint-key))))

(defn resolve*
  "Resolves an endpoint for a given service and region.

  service keyword Identify a AWS service (e.g. :s3)
  region keyword  Identify a AWS region (e.g. :us-east-1).

  Return a map with the following keys:

  :partition            The name of the partition.
  :region               The region of the endpoint.
  :hostname             The hostname to use.
  :sslCommonName        The sslCommonName to use (optional).
  :credentialScope      The Signature v4 credential scope (optional).
  :signatureVersions    A list of possible signature versions (optional).
  :protocols            A list of supported protocols."
  [service-key region]
  (some #(partition-resolve % service-key region)
        (:partitions (resolver))))

(def resolve (memoize resolve*))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol EndpointProvider
  (-fetch [provider region]))

(defn default-endpoint-provider [endpointPrefix endpoint-override]
  (reify EndpointProvider
    (-fetch [_ region]
      (if-let [ep (resolve (keyword endpointPrefix) (keyword region))]
        (merge ep (if (string? endpoint-override)
                    {:hostname endpoint-override}
                    endpoint-override))
        {:cognitect.anomalies/category :cognitect.anomalies/fault
         :cognitect.anomalies/message "No known endpoint."}))))

(defn fetch [provider region]
  (-fetch provider region))
