;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.client.api.async
  "DEPRECATED"
  (:require
   [cognitect.aws.client.protocol :as client.protocol]))

(defn ^:deprecated invoke
  "DEPRECATED: use cognitect.aws.client.api/invoke-async instead"
  [client op-map]
  (client.protocol/-invoke-async client op-map))