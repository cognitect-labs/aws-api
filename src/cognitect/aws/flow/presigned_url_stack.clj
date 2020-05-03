;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.flow.presigned-url-stack
  "Impl, don't call directly."
  (:require [cognitect.aws.signing :as signing]
            [cognitect.aws.flow.default-stack :as default-stack]))

(set! *warn-on-reflection* true)

(def add-presigned-query-string
  {:name "add presigned query-string"
   :f (fn [context]
        (select-keys (signing/presigned-url context) [:presigned-url :cognitect.aws.signing/basis]))})

(def presigned-url-stack
  "Returns a map of :presigned-url"
  [default-stack/load-service
   default-stack/check-op
   default-stack/add-http-client
   default-stack/add-region-provider
   default-stack/add-credentials-provider
   default-stack/add-endpoint-provider
   default-stack/provide-region
   default-stack/provide-credentials
   default-stack/provide-endpoint
   default-stack/build-http-request
   default-stack/add-endpoint
   default-stack/body-to-byte-buffer
   default-stack/http-interceptors
   add-presigned-query-string])
