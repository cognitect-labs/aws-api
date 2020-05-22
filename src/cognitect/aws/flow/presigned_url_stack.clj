;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.flow.presigned-url-stack
  "Impl, don't call directly."
  (:require [clojure.string :as str]
            [cognitect.aws.signing :as signing]
            [cognitect.aws.flow.default-stack :as default-stack]
            [cognitect.aws.flow.credentials-stack :as credentials-stack]))

(set! *warn-on-reflection* true)

;; TODO: (dchelimsky,2020-05-22) this is copied from signing/impl.
;; Move to util?
(defn- qs->map [qs]
  (->> (str/split (or qs "") #"&")
       (map #(str/split % #"=" 2))
       (into {})))

(def set-api-to-s3
  {:name "set api to s3"
   :f (fn [context] (assoc context :api :s3))})

(def add-presigned-query-string
  {:name "add presigned query-string"
   :f (fn [context]
        (select-keys (signing/presigned-url context) [:presigned-url :cognitect.aws.signing/basis]))})

(def add-presigned-http-request
  {:name "add http request"
   :f (fn [context]
        (if-let [url (get-in context [:presigned-url :url])]
          (assoc context :http-request
                 ;; TODO (dchelimsky,2020-05-22) this belongs in util
                 ;; or http namespace
                 (let [uri (java.net.URI. url)]
                   {:request-method :get
                    :scheme (.getScheme uri)
                    :server-name (.getHost uri)
                    :server-port 443
                    :uri (.getPath uri)
                    :query-string (.getQuery uri)}))
          context))})

(def add-op
  {:name "add op"
   :f (fn [{:keys [http-request] :as context}]
        (if http-request
          (assoc context :op
                 (some-> http-request
                         :query-string
                         qs->map
                         (get "Action")
                         keyword))
          context))})

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
