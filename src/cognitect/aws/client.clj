;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.client
  "Impl, don't call directly."
  (:require [clojure.core.async :as a]
            [cognitect.http-client :as http]
            [cognitect.aws.util :as util]
            [cognitect.aws.credentials :as credentials]))

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

(defmulti sign-http-request
  "Sign the HTTP request."
  (fn [service region credentials http-request]
    (get-in service [:metadata :signatureVersion])))

(defn handle-http-response
  [service op-map http-response]
  (try
    (if-let [anomaly-category (:cognitect.anomaly/category http-response)]
      {:cognitect.anomalies/category anomaly-category
       ::throwable (::http/throwable http-response)}
      (parse-http-response service op-map http-response))
    (catch Throwable t
      {:cognitect.anomalies/category :cognitect.anomalies/fault
       ::throwable t})))

(defn send-request
  "Send the request to AWS and return a channel which delivers the response."
  [client op-map]
  (let [meta-atom (atom {})]
    (try
      (let [{:keys [service region credentials endpoint http-client]} (-get-info client)
            {:keys [hostname]} endpoint
            http-request       (-> (build-http-request service op-map)
                                   (assoc-in [:headers "host"] hostname)
                                   (assoc :server-name hostname))
            http-request       (sign-http-request service region http-request (credentials/fetch credentials))]
        (swap! meta-atom assoc :http-request (update http-request :body util/bbuf->input-stream))
        (http/submit http-client http-request
                     (a/chan 1 (map #(with-meta
                                       (handle-http-response service op-map %)
                                       (assoc @meta-atom
                                              :http-response
                                              (update % :body util/bbuf->input-stream)))))))
      (catch Throwable t
        (let [err-ch (a/chan 1)]
          (a/put! err-ch (with-meta
                           {:cognitect.anomalies/category :cognitect.anomalies/fault
                            ::throwable                   t}
                           @meta-atom))
          err-ch)))))

(defn stop
  "Stop the client."
  [client]
  (http/stop (:http-client client))
  (credentials/stop (:credentials client)))
