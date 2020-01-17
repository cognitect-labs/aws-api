(ns cognitect.aws.client.shared
  (:require [cognitect.aws.http :as http]
            [cognitect.aws.credentials :as credentials]
            [cognitect.aws.region :as region]))

(declare http-client)

(def shared-http-client
  (delay (http/resolve-http-client nil)))
(def shared-credentials-provider
  (delay (credentials/default-credentials-provider (http-client))))
(def shared-region-provider
  (delay (region/default-region-provider (http-client))))

(defn http-client [] @shared-http-client)
(defn credentials-provider [] @shared-credentials-provider)
(defn region-provider [] @shared-region-provider)
