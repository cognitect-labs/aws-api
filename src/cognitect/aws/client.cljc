;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.client
  "Impl, don't call directly."
  (:require [clojure.core.async :as a]
            [cognitect.aws.util :as util]))

#?(:clj (set! *warn-on-reflection* true))

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

(defmulti sign-http-request
  "Sign the HTTP request."
  (fn [service endpoint credentials http-request]
    (get-in service [:metadata :signatureVersion])))

;; TODO convey throwable back from impl
(defn ^:private handle-http-response
  [service op-map http-response]
  (try
    (if (:cognitect.anomalies/category http-response)
      http-response
      (parse-http-response service op-map http-response))
    (catch Throwable t
      {:cognitect.anomalies/category :cognitect.anomalies/fault
       ::throwable t})))

(defn ^:private with-endpoint [req {:keys [protocol
                                           hostname
                                           port
                                           path]
                                    :as   endpoint}]
  (cond-> (-> req
              (assoc-in [:headers "host"] hostname)
              (assoc :server-name hostname))
    protocol (assoc :scheme protocol)
    port     (assoc :server-port port)
    path     (assoc :uri path)))

