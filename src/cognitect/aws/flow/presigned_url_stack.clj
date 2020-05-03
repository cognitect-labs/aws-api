;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.flow.presigned-url-stack
  "Impl, don't call directly."
  (:require [cognitect.aws.signing :as signing]
            [cognitect.aws.flow.default-stack :as default-stack]))

(set! *warn-on-reflection* true)

(def add-presigned-query-string-step
  {:name "add presigned query-string"
   :f (fn [context]
        (select-keys (signing/presigned-url context) [:presigned-url :cognitect.aws.signing/basis]))})

(def presigned-url-stack
  "Returns a map of :presigned-url"
  [default-stack/load-service-step
   default-stack/check-op-step
   default-stack/add-http-provider-step
   default-stack/add-region-provider-step
   default-stack/add-credentials-provider-step
   default-stack/add-endpoint-provider-step
   default-stack/fetch-region-step
   default-stack/fetch-credentials-step
   default-stack/discover-endpoint-step
   default-stack/build-http-request-step
   default-stack/add-endpoint-step
   default-stack/body-to-byte-buffer-step
   default-stack/http-interceptors-step
   add-presigned-query-string-step])
