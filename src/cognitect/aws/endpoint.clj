;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.endpoint
  "Impl, don't call directly."
  (:refer-clojure :exclude [resolve])
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [cognitect.aws.service :as service]
            [cognitect.aws.util :as util]))

(defn descriptor-resource-path [] (format "%s/endpoints.edn" service/base-resource-path))

(defn read-endpoints-description []
  (if-let [resource (io/resource (descriptor-resource-path))]
    (clojure.edn/read-string (slurp resource))
    (throw (ex-info (str "Cannot find resource " (descriptor-resource-path) ".") {}))))

(defn resolver
  "Create a new endpoint resolver."
  []
  (read-endpoints-description))

(defn render-template
  [template args]
  (str/replace template
               #"\{([^}]+)\}"
               #(get args (second %))))

(defn service-resolve
  "Resolve the endpoint for the given service."
  [partition service-name service endpoint-key]
  (let [endpoint      (get-in service [:endpoints endpoint-key])
        endpoint-name (name endpoint-key)
        result        (merge (:defaults partition)
                             (:defaults service)
                             endpoint
                             {:partition     (:partition partition)
                              :endpoint-name endpoint-name
                              :dnsSuffix     (:dnsSuffix partition)})]
    (util/map-vals #(render-template % {"service"   service-name
                                        "region"    endpoint-name
                                        "dnsSuffix" (:dnsSuffix partition)})
                   result
                   [:hostname :sslCommonName])))

(defn partition-resolve
  [{:keys [services] :as partition} service-key region-key]
  (let [{:keys [partitionEndpoint isRegionalized] :as service} (get services service-key)
        endpoint-key (if (and partitionEndpoint (not isRegionalized))
                       (keyword partitionEndpoint)
                       region-key)]
    (when (contains? (-> partition :regions keys set) (keyword region-key))
      (service-resolve partition (name service-key) service endpoint-key))))

(defn resolve*
  "Resolves an endpoint for a given service and region.

  service keyword Identify a AWS service (e.g. :s3)
  region keyword  Identify a AWS region (e.g. :us-east-1).

  Return a map with the following keys:

  :partition            The name of the partition.
  :endpoint-name        The name of the endpoint.
  :hostname             The hostname to use.
  :ssl-common-name      The ssl-common-name to use (optional).
  :credential-scope     The Signature v4 credential scope (optional).
  :signature-versions   A list of possible signature versions (optional).
  :protocols            A list of supported protocols."
  [service region]
  (some #(partition-resolve % service region)
        (:partitions (resolver))))

(def resolve (memoize resolve*))
