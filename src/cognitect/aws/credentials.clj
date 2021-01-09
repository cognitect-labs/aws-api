;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.credentials
  "Contains credentials providers and helpers for discovering credentials.

  Alpha. Subject to change."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.core.async :as a]
            [clojure.string :as str]
            [cognitect.aws.util :as u]
            [cognitect.aws.config :as config]
            [cognitect.aws.ec2-metadata-utils :as ec2])
  (:import (java.util Date)
           (java.util.concurrent Executors ExecutorService ScheduledExecutorService
                                 ScheduledFuture TimeUnit ThreadFactory)
           (java.io File)
           (java.net URI)
           (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(defprotocol CredentialsProvider
  (fetch [provider]
    "Return the credentials found by this provider, or nil.

    Credentials should be a map with the following keys:

    :aws/access-key-id                      string  required
    :aws/secret-access-key                  string  required
    :aws/session-token                      string  optional
    :cognitect.aws.credentials/ttl          number  optional  Time-to-live in seconds"))

(defprotocol Stoppable
  (-stop [_]))

(extend-protocol Stoppable
  Object
  (-stop [_]))

;; Credentials subsystem

(defonce ^:private scheduled-executor-service
  (delay
   (Executors/newScheduledThreadPool 1 (reify ThreadFactory
                                         (newThread [_ r]
                                           (doto (Thread. r)
                                             (.setName "cognitect.aws-api.credentials-provider")
                                             (.setDaemon true)))))))

(defn ^:skip-wiki refresh!
  "For internal use. Don't call directly.

  Invokes `(fetch provider)`, resets the `credentials-atom` with and
  returns the result.

  If the result includes a ::ttl, schedules a refresh after ::ttl
  seconds using `scheduler`, resetting `scheduled-refresh` with the
  resulting `ScheduledFuture`.

  If the credentials returned by the provider are not valid, resets
  both atoms to nil and returns nil."
  [credentials-atom scheduled-refresh-atom provider scheduler]
  (try
    (let [{:keys [::ttl] :as new-creds} (fetch provider)]
      (reset! scheduled-refresh-atom
              (when ttl
                (.schedule ^ScheduledExecutorService scheduler
                           ^Runnable #(refresh! credentials-atom scheduled-refresh-atom provider scheduler)
                           ^long ttl
                           TimeUnit/SECONDS)))
      (reset! credentials-atom new-creds))
    (catch Throwable t
      (reset! scheduled-refresh-atom nil)
      (log/error t "Error fetching credentials."))))

(defn cached-credentials-with-auto-refresh
  "Returns a CredentialsProvider which wraps `provider`, caching
  credentials returned by `fetch`, and auto-refreshing the cached
  credentials in a background thread when the credentials include a
  ::ttl.

  Call `stop` to cancel future auto-refreshes.

  The default ScheduledExecutorService uses a ThreadFactory that
  spawns daemon threads. You can override this by providing your own
  ScheduledExecutorService.

  Alpha. Subject to change."
  ([provider]
   (cached-credentials-with-auto-refresh provider @scheduled-executor-service))
  ([provider scheduler]
   (let [credentials-atom       (atom nil)
         scheduled-refresh-atom (atom nil)]
     (reify
       CredentialsProvider
       (fetch [_]
         (or @credentials-atom
             (refresh! credentials-atom scheduled-refresh-atom provider scheduler)))
       Stoppable
       (-stop [_]
         (-stop provider)
         (when-let [r @scheduled-refresh-atom]
           (.cancel ^ScheduledFuture r true)))))))

(defn ^:deprecated auto-refreshing-credentials
  "Deprecated. Use cached-credentials-with-auto-refresh"
  ([provider] (cached-credentials-with-auto-refresh provider))
  ([provider scheduler] (cached-credentials-with-auto-refresh provider scheduler)))

(defn stop
  "Stop auto-refreshing the credentials.

  Alpha. Subject to change."
  [credentials]
  (-stop credentials)
  nil)

(defn ^:skip-wiki valid-credentials
  "For internal use. Don't call directly."
  ([credentials]
   (valid-credentials credentials nil))
  ([{:keys [aws/access-key-id aws/secret-access-key] :as credentials}
    credential-source]
   (if (and (not (str/blank? access-key-id))
            (not (str/blank? secret-access-key)))
     credentials
     (when credential-source
       (log/info (str "Unable to fetch credentials from " credential-source "."))
       nil))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Providers

(defn chain-credentials-provider
  "Returns a credentials-provider which chains together multiple
  credentials providers.

  `fetch` calls each provider in order until one returns a non-nil
  result. This provider is then cached for future calls to `fetch`.

  `fetch` returns nil if none of the providers return credentials.

  Alpha. Subject to change."
  [providers]
  (let [cached-provider (atom nil)]
    (reify
      CredentialsProvider
      (fetch [_]
        (valid-credentials
         (if @cached-provider
           (fetch @cached-provider)
           (some (fn [provider]
                   (when-let [creds (fetch provider)]
                     (reset! cached-provider provider)
                     creds))
                 providers))
         "any source"))
      Stoppable
      (-stop [_] (run! -stop providers)))))

(defn environment-credentials-provider
  "Return the credentials from the environment variables.

  Look at the following variables:
  * AWS_ACCESS_KEY_ID      required
  * AWS_SECRET_ACCESS_KEY  required
  * AWS_SESSION_TOKEN      optional

  Returns nil if any of the required variables is blank.

  Logs error if one required variable is blank but the other
  is not.

  Alpha. Subject to change."
  []
  (cached-credentials-with-auto-refresh
   (reify CredentialsProvider
     (fetch [_]
       (valid-credentials
        {:aws/access-key-id     (u/getenv "AWS_ACCESS_KEY_ID")
         :aws/secret-access-key (u/getenv "AWS_SECRET_ACCESS_KEY")
         :aws/session-token     (u/getenv "AWS_SESSION_TOKEN")}
        "environment variables")))))

(defn system-property-credentials-provider
  "Return the credentials from the system properties.

  Look at the following properties:
  * aws.accessKeyId  required
  * aws.secretKey    required

  Returns nil if any of the required properties is blank.

  Logs error if one of the required properties is blank but
  the other is not.

  Alpha. Subject to change. "
  []
  (cached-credentials-with-auto-refresh
   (reify CredentialsProvider
     (fetch [_]
       (valid-credentials
        {:aws/access-key-id     (u/getProperty "aws.accessKeyId")
         :aws/secret-access-key (u/getProperty "aws.secretKey")
         :aws/session-token     (u/getProperty "aws.sessionToken")}
        "system properties")))))

(defn profile-credentials-provider
  "Return credentials in an AWS configuration profile.

  Arguments:

  profile-name  string  The name of the profile in the file. (default: default)
  f             File    The profile configuration file. (default: ~/.aws/credentials)

  https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html
    Parsed properties:

    aws_access_key        required
    aws_secret_access_key required
    aws_session_token     optional

  Alpha. Subject to change."
  ([]
   (profile-credentials-provider (or (u/getenv "AWS_PROFILE")
                                     (u/getProperty "aws.profile")
                                     "default")))
  ([profile-name]
   (profile-credentials-provider profile-name (or (io/file (u/getenv "AWS_CREDENTIAL_PROFILES_FILE"))
                                                  (io/file (u/getProperty "user.home") ".aws" "credentials"))))
  ([profile-name ^File f]
   (cached-credentials-with-auto-refresh
    (reify CredentialsProvider
      (fetch [_]
        (when (.exists f)
          (try
            (let [profile (get (config/parse f) profile-name)]
              (valid-credentials
               {:aws/access-key-id     (get profile "aws_access_key_id")
                :aws/secret-access-key (get profile "aws_secret_access_key")
                :aws/session-token     (get profile "aws_session_token")}
               "aws profiles file"))
            (catch Throwable t
              (log/error t "Error fetching credentials from aws profiles file")))))))))

(defn calculate-ttl
  "Primarily for internal use, returns time to live (ttl, in seconds),
  based on `:Expiration` in credentials.  If `credentials` contains no
  `:Expiration`, defaults to 3600.

  `:Expiration` can be a string parsable by java.time.Instant/parse
  (returned by ec2/ecs instance credentials) or a java.util.Date
  (returned from :AssumeRole on aws sts client)."
  [{:keys [Expiration] :as credentials}]
  (if Expiration
    (let [expiration (if (instance? Date Expiration)
                       (.toInstant ^Date Expiration)
                       (Instant/parse Expiration))]
      (max (- (.getSeconds (Duration/between (Instant/now) ^Instant expiration)) 300)
           60))
    3600))

(defn container-credentials-provider
  "For internal use. Do not call directly.

  Return credentials from ECS iff one of
  AWS_CONTAINER_CREDENTIALS_RELATIVE_URI or
  AWS_CONTAINER_CREDENTIALS_FULL_URI is set.

  Alpha. Subject to change."
  [http-client]
  (cached-credentials-with-auto-refresh
   (reify CredentialsProvider
     (fetch [_]
       (when-let [creds (ec2/container-credentials http-client)]
         (valid-credentials
          {:aws/access-key-id     (:AccessKeyId creds)
           :aws/secret-access-key (:SecretAccessKey creds)
           :aws/session-token     (:Token creds)
           ::ttl                  (calculate-ttl creds)}
          "ecs container"))))))

(defn instance-profile-credentials-provider
  "For internal use. Do not call directly.

  Return credentials from EC2 metadata service iff neither of
  AWS_CONTAINER_CREDENTIALS_RELATIVE_URI or
  AWS_CONTAINER_CREDENTIALS_FULL_URI
  is set.

  Alpha. Subject to change."
  [http-client]
  (cached-credentials-with-auto-refresh
   (reify CredentialsProvider
     (fetch [_]
       (when-let [creds (ec2/instance-credentials http-client)]
         (valid-credentials
          {:aws/access-key-id     (:AccessKeyId creds)
           :aws/secret-access-key (:SecretAccessKey creds)
           :aws/session-token     (:Token creds)
           ::ttl                  (calculate-ttl creds)}
          "ec2 instance"))))))

(defn default-credentials-provider
  "Returns a chain-credentials-provider with (in order):

    environment-credentials-provider
    system-property-credentials-provider
    profile-credentials-provider
    container-credentials-provider
    instance-profile-credentials-provider

  Alpha. Subject to change."
  [http-client]
  (chain-credentials-provider
   [(environment-credentials-provider)
    (system-property-credentials-provider)
    (profile-credentials-provider)
    (container-credentials-provider http-client)
    (instance-profile-credentials-provider http-client)]))

(defn basic-credentials-provider
  "Given a map with :access-key-id and :secret-access-key,
  returns an implementation of CredentialsProvider which returns
  those credentials on fetch.

  Alpha. Subject to change."
  [{:keys [access-key-id secret-access-key]}]
  (assert access-key-id "Missing")
  (assert secret-access-key "Missing")
  (reify CredentialsProvider
    (fetch [_]
      {:aws/access-key-id     access-key-id
       :aws/secret-access-key secret-access-key})))

(defn fetch-async
  "Returns a channel that will produce the result of calling fetch on
  the provider.

  Alpha. Subject to change."
  [provider]
  (u/fetch-async fetch provider "credentials"))
