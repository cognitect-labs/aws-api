;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.client.shared
  (:require [cognitect.aws.http :as http]
            [cognitect.aws.credentials :as credentials]
            [cognitect.aws.region :as region]))

(set! *warn-on-reflection* true)

(def ^:private shared-http-client
     (atom nil))

(defn http-client
  "Returns the globally shared instance of http-client (created on the
  first call).

  Alpha. Subject to change."
  []
  (or @shared-http-client
      (reset! shared-http-client (http/resolve-http-client nil))))

(def ^:private shared-credentials-provider
     (delay (credentials/default-credentials-provider (http-client))))

(defn credentials-provider
  "Returns the globally shared instance of credentials-provider, which
  uses the globally shared instance of http-client.

  Alpha. Subject to change."
  []
  @shared-credentials-provider)

(def ^:private shared-region-provider
     (delay (region/default-region-provider (http-client))))

(defn region-provider
  "Returns the globally shared instance of region-provider, which
  uses the globally shared instance of http-client.

  Alpha. Subject to change."
  []
  @shared-region-provider)

#_:clj-kondo/ignore
(defn shared-http-client?
  "For internal use.

  Alpha. Subject to change."
  [candidate-http-client]
  (identical? candidate-http-client
              @shared-http-client))
