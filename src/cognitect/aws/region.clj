;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.region
  "Region providers. Primarily for internal use, and subject to change."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [cognitect.aws.util :as u]
            [cognitect.aws.config :as config]
            [cognitect.aws.ec2-metadata-utils :as ec2])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(defn ^:skip-wiki valid-region
  "For internal use. Don't call directly.

  Return the credential region if valid, otherwise nil."
  [region]
  ;; TODO: (dchelimsky 2018-07-27) maybe validate this against known regions?
  (when-not (str/blank? region) region))

(defprotocol RegionProvider
  (fetch [_] "Returns the region found by this provider, or nil."))

(defn chain-region-provider
  "Chain together multiple region providers.

  `fetch` calls each provider in order until one returns a non-nil result,
  or returns nil.

  Alpha. Subject to change."
  [providers]
  (reify RegionProvider
    (fetch [_]
      (or (valid-region (some fetch providers))
          (throw (ex-info "No region found by any region provider."
                          {:providers (map class providers)}))))))

(defn environment-region-provider
  "Returns the region from the AWS_REGION env var, or nil if not present.

  Alpha. Subject to change."
  []
  (reify RegionProvider
    (fetch [_] (valid-region (u/getenv "AWS_REGION")))))

(defn system-property-region-provider
  "Returns the region from the aws.region system property, or nil if not present.

  Alpha. Subject to change."
  []
  (reify RegionProvider
    (fetch [_] (valid-region (u/getProperty "aws.region")))))

(defn profile-region-provider
  "Returns the region from an AWS configuration profile.

  Arguments:

    f             File    The profile configuration file. (default: ~/.aws/config)
    profile-name  string  The name of the profile in the file. (default: default)

  Parsed properties:

    region        required

  Alpha. Subject to change."
  ([]
   (profile-region-provider (or (u/getenv "AWS_PROFILE")
                                (u/getProperty "aws.profile")
                                "default")))
  ([profile-name]
   (profile-region-provider profile-name (or (io/file (u/getenv "AWS_CONFIG_FILE"))
                                             (io/file (u/getProperty "user.home") ".aws" "config"))))
  ([profile-name ^File f]
   (reify RegionProvider
     (fetch [_]
       (when (.exists f)
        (try
          (let [profile (get (config/parse f) profile-name)]
            (valid-region (get profile "region")))
          (catch Throwable t
            (log/error t "Unable to fetch region from the AWS config file " (str f)))))))))

(defn instance-region-provider
  "Returns the region from the ec2 instance's metadata service,
  or nil if the service can not be found.

  Alpha. Subject to change."
  [http-client]
  (let [cached-region (atom nil)]
    (reify RegionProvider
      (fetch [_]
        (or @cached-region
            (reset! cached-region (valid-region (ec2/get-ec2-instance-region http-client))))))))

(defn default-region-provider
  "Returns a chain-region-provider with, in order:

    environment-region-provider
    system-property-region-provider
    profile-region-provider
    instance-region-provider

  Alpha. Subject to change."
  [http-client]
  (chain-region-provider
   [(environment-region-provider)
    (system-property-region-provider)
    (profile-region-provider)
    (instance-region-provider http-client)]))

(defn fetch-async
  "Returns a channel that will produce the result of calling fetch on
  the provider.

  Alpha. Subject to change."
  [provider]
  (u/fetch-async fetch provider "region"))
