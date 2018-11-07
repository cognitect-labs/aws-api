;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.region
  "Implements the region subsystem."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [cognitect.aws.util :as util]
            [cognitect.aws.ec2-metadata-utils :as ec2])
  (:import [java.util.concurrent Executors ScheduledExecutorService]
           [java.util.concurrent TimeUnit]
           [java.io File]))

(defn valid-region
  "Return the credential region if valid, otherwise nil."
  [region]
  ;; TODO: (dchelimsky 2018-07-27) maybe validate this against known regions?
  (when-not (str/blank? region) region))

(defprotocol RegionProvider
  (fetch [_]
    "Return the region found by this provider, or nil.

    Return a map with the following keys:

    :aws/region                        string  required "))

;; Providers

(defn chain-region-provider
  "Chain together multiple region providers.

  Calls each provider in order until one return a non-nil result.

  Returns nil if none of the providers return a region"
  [providers]
  (reify RegionProvider
    (fetch [_]
      (or (valid-region (some fetch providers))
          (throw (ex-info "No region found by any region provider."
                          {:providers (map class providers)}))))))

(defn environment-region-provider
  "Return the credentials from the environment variables.

  Look at the following variables:
  * AWS_REGION      required

  Returns nil if any of the required variables is blank."
  []
  (reify RegionProvider
    (fetch [_] (valid-region (System/getenv "AWS_REGION")))))

(defn system-property-region-provider
  "Return the region from the system properties.

  Look at the following properties:
  * aws.region  required

  Returns nil the required property is blank."
  []
  (reify RegionProvider
    (fetch [_] (valid-region (System/getProperty "aws.region")))))

(defn profile-region-provider
  "Return the region in a AWS configuration profile.

  Arguments:

  f             File    The profile configuration file. (default: ~/.aws/config)
  profile-name  string  The name of the profile in the file. (default: default)

  Parsed properties:

  region        required
  "
  ([]
   (profile-region-provider
     (or (io/file (System/getenv "AWS_CREDENTIAL_PROFILES_FILE"))
         (io/file (System/getProperty "user.home") ".aws" "config"))))
  ([f]
   (profile-region-provider f "default"))
  ([^File f profile-name]
   (reify RegionProvider
     (fetch [_]
       (when (.exists f)
        (try
          (let [profile (get (util/config->profiles f) profile-name)]
            (valid-region (get profile "region")))
          (catch Throwable t
            (log/error t "Unable to fetch region from the AWS config file " (str f)))))))))

(defn instance-region-provider
  "Return the region from the ec2 instance's metadata service.

  Returns nil the required property cannot be reached."
  []
  (reify RegionProvider
    (fetch [_] (valid-region (ec2/get-ec2-instance-region)))))


(defn default-region-provider
  "Return a chain-region-provider comprising, in order:

    environment-region-provider
    system-property-region-provider
    profile-region-provider"
  []
  (chain-region-provider
   [(environment-region-provider)
    (system-property-region-provider)
    (profile-region-provider)
    (instance-region-provider)]))
