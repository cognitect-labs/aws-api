;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.client
  "Impl, don't call directly."
  (:require [clojure.core.async :as a]
            [cognitect.http-client :as http]
            [cognitect.aws.util :as util]
            [cognitect.aws.credentials :as credentials]))

(set! *warn-on-reflection* true)

(defprotocol ClientSPI
  (-get-info [_] "Intended for internal use only"))

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
      {:cognitect.anomalies/category :cogniect.anomalies/fault
       ::throwable t})))

(defn send-request
  "Send the request to AWS and return a channel which delivers the response."
  [client op-map]
  (let [{:keys [service region credentials endpoint http-client]} (-get-info client)
        {:keys [hostname]} endpoint
        resp-chan (a/chan 1 (map #(with-meta
                                    (handle-http-response service op-map %)
                                    (update % :body util/bbuf->str))))]
    (try
      (let [http-request (-> (build-http-request service op-map)
                             (assoc-in [:headers "host"] hostname)
                             (assoc :server-name hostname))
            http-request (sign-http-request service region http-request @credentials)]
        (http/submit http-client http-request resp-chan))
      (catch Throwable t
        (a/put! resp-chan {:cognitect.anomalies/category :cognitect.anomalies/fault
                           ::throwable t})))
    resp-chan))

(defn stop
  "Stop the client."
  [client]
  (http/stop (:http-client client))
  (credentials/stop (:credentials client)))
