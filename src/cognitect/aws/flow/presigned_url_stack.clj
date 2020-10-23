;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.flow.presigned-url-stack
  "Impl, don't call directly."
  (:require [clojure.string :as str]
            [cognitect.aws.signing :as signing]
            [cognitect.aws.flow.default-stack :as default-stack]
            [cognitect.aws.flow.credentials-stack :as credentials-stack]
            [cognitect.aws.flow.util :refer [defstep]]
            [cognitect.aws.util :as util]))

(set! *warn-on-reflection* true)

(defstep set-api-to-s3 [context] (assoc context :api :s3))

(defstep add-presigned-query-string [context]
  (select-keys (signing/presigned-url context) [:presigned-url :cognitect.aws.signing/basis]))

(defstep add-presigned-http-request [context]
  (if-let [url (get-in context [:presigned-url :url])]
    (assoc context :http-request
           (let [uri (java.net.URI. url)]
             {:request-method :get
              :scheme         (.getScheme uri)
              :server-name    (.getHost uri)
              :server-port    443
              :uri            (.getPath uri)
              ;; extract the encoded query string manually instead
              ;; of getting the decoded query strings from the URI.
              :query-string   (last (str/split url #"\?"))}))
    context))

(defstep add-op [{:keys [http-request] :as context}]
  (if http-request
    (assoc context :op
           (some-> http-request
                   :query-string
                   util/query-string->map
                   (get "Action")
                   keyword))
    context))

(def presigned-url-stack
  "Returns a map of :presigned-url"
  [set-api-to-s3
   default-stack/load-service
   default-stack/check-op
   default-stack/add-http-client
   default-stack/add-region-provider
   default-stack/provide-region
   (credentials-stack/process-credentials)
   default-stack/add-endpoint-provider
   default-stack/provide-endpoint
   default-stack/build-http-request
   default-stack/apply-endpoint
   default-stack/body-to-byte-buffer
   default-stack/http-interceptors
   add-presigned-query-string])

(def fetch-presigned-url-stack
  [set-api-to-s3
   add-presigned-http-request
   add-op
   default-stack/load-service
   default-stack/add-http-client
   default-stack/send-request
   default-stack/decode-response])
