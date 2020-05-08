;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.client
  "Impl, don't call directly."
  (:require [clojure.core.async :as a]
            [cognitect.aws.http :as http]
            [cognitect.aws.util :as util]
            [cognitect.aws.interceptors :as interceptors]
            [cognitect.aws.dynaload :as dynaload]
            [cognitect.aws.endpoint :as endpoint]
            [cognitect.aws.region :as region]
            [cognitect.aws.credentials :as credentials]
            [cognitect.aws.service :as service]
            [cognitect.aws.signing :as signing]
            [cognitect.aws.signing.impl] ;; implements multimethods
            [cognitect.aws.client.shared :as shared]
            [cognitect.aws.flow :as flow]))

(set! *warn-on-reflection* true)

(defprotocol ClientSPI
  (-get-info [_] "Intended for internal use only"))

(deftype Client [client-meta info]
  clojure.lang.IObj
  (meta [_] @client-meta)
  (withMeta [this m] (swap! client-meta merge m) this)

  ClientSPI
  (-get-info [_] info))

(defmulti build-http-request
  "AWS request -> HTTP request."
  (fn [service op-map]
    (get-in service [:metadata :protocol])))

(defmulti parse-http-response
  "HTTP response -> AWS response"
  (fn [service op-map http-response]
    (get-in service [:metadata :protocol])))

;; TODO convey throwable back from impl
(defn handle-http-response
  "Internal use.

  Alpha. Subject to change."
  [service op-map http-response]
  (try
    (if (:cognitect.anomalies/category http-response)
      http-response
      (parse-http-response service op-map http-response))
    (catch Throwable t
      {:cognitect.anomalies/category :cognitect.anomalies/fault
       ::throwable t})))

(defn ^:private put-throwable [result-ch t response-meta op-map]
  (a/put! result-ch (with-meta
                      {:cognitect.anomalies/category :cognitect.anomalies/fault
                       ::throwable                   t}
                      (swap! response-meta
                             assoc :op-map op-map))))

(defn send-request
  "For internal use. Send the request to AWS and return a channel which delivers the response.

  Alpha. Subject to change."
  [client op-map stk]
  (let [request (-> client
                    -get-info
                    (merge op-map)
                    (assoc :executor (java.util.concurrent.ForkJoinPool/commonPool)))]
    (flow/execute request stk)))

(comment
  (require '[cognitect.aws.client.api :as aws]
           '[cognitect.aws.diagnostics :as diagnostics])

  (System/setProperty "aws.profile" "REDACTED")

  (set! *print-level* 10)

  (def c (aws/client {:api :s3}))

  (aws/invoke c {:op :ListBuckets})

  (def list-buckets-response (aws/invoke c {:op :ListBuckets}))

  (diagnostics/summarize-log list-buckets-response)

  (diagnostics/log list-buckets-response)

  (diagnostics/trace-key list-buckets-response :http-request)

  (diagnostics/trace-key list-buckets-response :service)

  (def bucket (-> list-buckets-response :Buckets first :Name))

  (aws/invoke c {:op :ListObjects
                 :request {:Bucket bucket}
                 :timeout 30})

  (aws/invoke c {:op :ListObjectsV2
                 :request {:Bucket bucket}
                 :timeout 30})

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; presigned requests

  (require '[cognitect.aws.flow.presigned-url-stack :as presigned-url-stack])

  (defn curl [url] (clojure.java.shell/sh "curl" url))

  ;; ListBuckets
  (def list-buckets-url
    (:presigned-url (aws/invoke c {:op :ListBuckets
                                   :timeout 30}
                                cognitect.aws.flow.presigned-url-stack/presigned-url-stack)))

  (curl list-buckets-url)

  ;; ListObjects
  ;; - assumes bucket is defined from ListBuckets, above

  (def list-objects-url
    (:presigned-url (aws/invoke c {:op :ListObjects
                                   :request {:Bucket bucket}
                                   :timeout 30}
                                cognitect.aws.flow.presigned-url-stack/presigned-url-stack)))

  (curl list-objects-url)

  ;; ListObjectsV2

  (def list-objects-v2-url
    (:presigned-url (aws/invoke c {:op :ListObjectsV2
                                   :request {:Bucket bucket}
                                   :timeout 30}
                                cognitect.aws.flow.presigned-url-stack/presigned-url-stack)))

  (curl list-objects-v2-url)

)
