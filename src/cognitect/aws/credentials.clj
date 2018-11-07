;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.credentials
  "Implement the credentials subsystem."
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [cognitect.aws.util :as util]
    [cognitect.aws.ec2-metadata-utils :as ec2-metadata-utils])
  (:import [java.util.concurrent Executors ScheduledExecutorService]
           [java.util.concurrent TimeUnit]
           [java.io File]
           (java.net URI)))

(def ^:const ecs-container-credentials-path-env-var "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI")
(def ^:const ecs-container-credentials-full-uri-env-var "AWS_CONTAINER_CREDENTIALS_FULL_URI")
(def ^:const ec2-security-credentials-resource "/latest/meta-data/iam/security-credentials/")

(def allowed-hosts #{"127.0.0.1" "localhost"})

(defprotocol CredentialsProvider
  (fetch [_]
    "Return the credentials found by this provider, or nil.

    Return a map with the following keys:


    :aws/access-key-id                      string  required
    :aws/secret-access-key                  string  required
    :aws/session-token                      string  optional
    :cognitect.aws.core.credentials/ttl   number  optional  Time-to-live in seconds"))



;; Credentials subsystem

(defn auto-refresh-fn
  "Return the function to auto-refresh the `credentials` atom using the given `provider`.

  If the credentials return a ::ttl, schedule refresh after ::ttl seconds using `scheduler`.

  If the credentials returned by the provider are not valid, an error will be logged and
  the automatic refresh process will stop."
  [credentials provider scheduler]
  (fn refresh! []
    (try
      (let [{:keys [::ttl] :as new-creds} (fetch provider)]
        (reset! credentials new-creds)
        (when ttl
          (.schedule ^ScheduledExecutorService scheduler
                     ^Runnable refresh!
                     ^long ttl
                     TimeUnit/SECONDS)))
      (catch Throwable t
        (log/error t "Error fetching the credentials.")))))

(defn auto-refreshing-credentials
  "Create auto-refreshing credentials using the specified provider.

  Return a derefable containing the credentials.

  Call `stop` to stop the auto-refreshing process.

  A ScheduledExecutorService can be provided."
  ([provider]
   (auto-refreshing-credentials provider (Executors/newScheduledThreadPool 1)))
  ([provider scheduler]
   (let [credentials (atom nil)
         auto-refresh! (auto-refresh-fn credentials provider scheduler)]
     (auto-refresh!)
     (alter-meta! credentials assoc ::scheduler scheduler)
     credentials)))

(defn stop
  "Stop auto-refreshing the credentials."
  [credentials]
  (when-let [{:keys [::scheduler]} (meta credentials)]
    (.shutdownNow ^ScheduledExecutorService scheduler)))

(defn valid-credentials
  ([credentials]
   (valid-credentials credentials nil))
  ([{:keys [aws/access-key-id aws/secret-access-key] :as credentials}
    location]
   (cond (and (not (str/blank? access-key-id))
              (not (str/blank? secret-access-key)))
         credentials

         (or (str/blank? access-key-id) (str/blank? secret-access-key))
         (do
           (when-not (nil? location)
             (log/error (str "Error fetching credentials from " location ".")))
           nil)

         :else
         nil)))

;; Providers

(defn chain-credentials-provider
  "Chain together multiple credentials provider.

  Calls each provider in order until one return a non-nil result. This
  provider is then cached for future calls to `fetch`.

  Returns nil if none of the providers return credentials."
  [providers]
  (let [cached-provider (atom nil)]
    (reify CredentialsProvider
      (fetch [_]
        (valid-credentials
         (if @cached-provider
           (fetch @cached-provider)
           (some (fn [provider]
                   (when-let [creds (fetch provider)]
                     (reset! cached-provider provider)
                     creds))
                 providers))
         "any source")))))

(defn environment-credentials-provider
  "Return the credentials from the environment variables.

  Look at the following variables:
  * AWS_ACCESS_KEY_ID      required
  * AWS_SECRET_ACCESS_KEY  required
  * AWS_SESSION_TOKEN      optional

  Returns nil if any of the required variables is blank.

  Logs error if one required variable is blank but the other
  is not."
  ([]
   (environment-credentials-provider (System/getenv)))
  ([env]
   (reify CredentialsProvider
     (fetch [_]
       (valid-credentials
         {:aws/access-key-id     (get env "AWS_ACCESS_KEY_ID")
          :aws/secret-access-key (get env "AWS_SECRET_ACCESS_KEY")
          :aws/session-token     (get env "AWS_SESSION_TOKEN")}
         "environment variables")))))

(defn system-property-credentials-provider
  "Return the credentials from the system properties.

  Look at the following properties:
  * aws.accessKeyId  required
  * aws.secretKey    required

  Returns nil if any of the required properties is blank.

  Logs error if one of the required properties is blank but
  the other is not."
  ([]
   (system-property-credentials-provider (System/getProperties)))
  ([properties]
   (reify CredentialsProvider
     (fetch [_]
       (valid-credentials
        {:aws/access-key-id     (get properties "aws.accessKeyId")
         :aws/secret-access-key (get properties "aws.secretKey")}
        "system properties")))))

(defn profile-credentials-provider
  "Return credentials in a AWS configuration profile.

  Arguments:

  profile-name  string  The name of the profile in the file. (default: default)
  f             File    The profile configuration file. (default: ~/.aws/credentials)

  https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html
    Parsed properties:

    aws_access_key        required
    aws_secret_access_key required
    aws_session_token     optional"
  ([]
   (profile-credentials-provider "default"))
  ([profile-name]
   (profile-credentials-provider profile-name (or (io/file (System/getenv "AWS_CREDENTIAL_PROFILES_FILE"))
                                                  (io/file (System/getProperty "user.home") ".aws" "credentials"))))
  ([profile-name ^File f]
   (reify CredentialsProvider
     (fetch [_]
       (when (.exists f)
        (try
          (let [profile (get (util/config->profiles f) profile-name)]
            (valid-credentials
             {:aws/access-key-id     (get profile "aws_access_key_id")
              :aws/secret-access-key (get profile "aws_secret_access_key")
              :aws/session-token     (get profile "aws_session_token")}
             "aws profiles file"))
          (catch Throwable t
            (log/error t "Error fetching credentials from aws profiles file"))))))))

(defn container-credentials-provider
  "Return credentials from ECS iff AWS_CONTAINER_CREDENTIALS_RELATIVE_URI is set."
  ([env]
   (reify CredentialsProvider
     (fetch [_]
       (when-let [container-creds
                  (some-> (or (when-let [relative-uri (get env ecs-container-credentials-path-env-var)]
                                (some->> (ec2-metadata-utils/get-items-at-path relative-uri)
                                         first
                                         (str relative-uri)
                                         ec2-metadata-utils/get-data-at-path))

                              (when-let [full-uri (get env ecs-container-credentials-full-uri-env-var)]
                                (some->> (ec2-metadata-utils/get-items full-uri {})
                                         first
                                         (str full-uri)
                                         (URI.)
                                         ec2-metadata-utils/get-data)))
                          (json/read-str :key-fn keyword))]
           (valid-credentials
             {:aws/access-key-id (:AccessKeyId container-creds)
              :aws/secret-access-key (:SecretAccessKey container-creds)
              :aws/session-token (:Token container-creds)})))))
  ([]
   (container-credentials-provider (System/getenv))))

(defn instance-profile-credentials-provider
  "Return credentials from EC2 metadata service iff
  AWS_CONTAINER_CREDENTIALS_RELATIVE_URI is not set."
  ([env]
   (reify CredentialsProvider
     (fetch [_]
       (when-not (System/getenv ecs-container-credentials-path-env-var)
         (when-let [cred-name (first (ec2-metadata-utils/get-items-at-path ec2-security-credentials-resource))]
           (let [creds (some-> (ec2-metadata-utils/get-data-at-path (str ec2-security-credentials-resource cred-name))
                               (json/read-str :key-fn keyword))]
             (valid-credentials
               {:aws/access-key-id (:AccessKeyId creds)
                :aws/secret-access-key (:SecretAccessKey creds)
                :aws/session-token (:Token creds)})))))))
  ([]
   (instance-profile-credentials-provider (System/getenv))))

(defn default-credentials-provider
  "Return a chain-credentials-provider comprising, in order:

    environment-credentials-provider
    system-property-credentials-provider
    profile-credentials-provider
    container-credentials-provider (Not Yet Implemented)
    instance-profile-credentials-provider (Not Yet Implemented)"
  []
  (chain-credentials-provider
   [(environment-credentials-provider)
    (system-property-credentials-provider)
    (profile-credentials-provider)
    (container-credentials-provider)
    (instance-profile-credentials-provider)]))
