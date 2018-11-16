;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.client
  "A generic client for Amazon web services."
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
  [{:keys [service]} {op-map ::http/meta :as http-response}]
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
        resp-chan (a/chan 1)]
    (try
      (let [{:keys [hostname]} endpoint
            http-request (-> (build-http-request service op-map)
                             (assoc-in [:headers "host"] hostname)
                             (assoc :server-name hostname)
                             (assoc ::http/meta op-map))
            http-request (sign-http-request service region http-request @credentials)
            c (a/chan 1)]
        (http/submit http-client http-request c)
        (a/go
          (let [http-response (a/<! c)]
            (a/put! resp-chan
                    (with-meta (handle-http-response (-get-info client) http-response)
                      (-> http-response
                          (dissoc ::http/meta)
                          (update :body util/bbuf->str)))))))
      (catch Throwable t
        (a/put! resp-chan {:cognitect.anomalies/category :cognitect.anomalies/fault
                           ::throwable t})))
    resp-chan))

(defn with-retry
  "Calls req-fn, a function that wraps some operation and returns a
  channel. When the response to req-fn is retriable? and backoff
  returns an int, waits backoff ms and retries, otherwise puts
  response on resp-chan."
  [req-fn resp-chan retriable? backoff]
  (a/go-loop [retries 0]
    (let [resp (a/<! (req-fn))]
      (if (retriable? resp)
        (if-let [bo (backoff retries)]
          (do
            (a/<! (a/timeout bo))
            (recur (inc retries)))
          (a/>! resp-chan resp))
        (a/>! resp-chan resp))))
  resp-chan)

(defn stop
  "Stop the client."
  [client]
  (http/stop (:http-client client))
  (credentials/stop (:credentials client)))
