;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.client
  "Impl, don't call directly."
  (:require [clojure.core.async :as a]
            [cognitect.aws.http :as http]
            [cognitect.aws.util :as util]
            [cognitect.aws.interceptors :as interceptors]
            [cognitect.aws.credentials :as credentials])
  (:import [java.util.concurrent Callable ExecutorService Executors ThreadFactory]))

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

;; TODO convey throwable back from impl
(defn ^:private handle-http-response
  [service op-map http-response]
  (try
    (if-let [anomaly-category (:cognitect.anomaly/category http-response)]
      {:cognitect.anomalies/category anomaly-category
       ::throwable (::http/throwable http-response)}
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

(defn- send-request*
  "*Blocking* helper, call only from send-request."
  [client op-map]
  (let [result-meta (atom {})]
    (try
      (let [{:keys [service region credentials endpoint http-client]} (-get-info client)
            http-request (sign-http-request service region (credentials/fetch credentials)
                                            (-> (build-http-request service op-map)
                                                (with-endpoint endpoint)
                                                (update :body util/->bbuf)
                                                ((partial interceptors/modify-http-request service op-map))))]
        (swap! result-meta assoc :http-request http-request)
        (http/submit http-client
                     http-request
                     (a/chan 1 (map #(with-meta
                                       (handle-http-response service op-map %)
                                       (assoc @result-meta
                                              :http-response
                                              (update % :body util/bbuf->input-stream)))))))
      (catch Throwable t
        (let [err-ch (a/chan 1)]
          (a/put! err-ch (with-meta
                           {:cognitect.anomalies/category :cognitect.anomalies/fault
                            ::throwable                   t}
                           @result-meta))
          err-ch)))))

(defonce send-pool
  (delay
   (let [idx (atom 0)]
     (Executors/newFixedThreadPool
      4
      (reify ThreadFactory
             (newThread
              [_ runnable]
              (doto (.newThread (Executors/defaultThreadFactory) runnable)
                (.setName (str "cognitect-aws-send-" (swap! idx inc)))
                (.setDaemon true))))))))

(defn send-request
  "Send the request to AWS and return a channel which delivers the response."
  [client op-map]
  (let [ch (a/chan 1)]
    (.submit ^ExecutorService @send-pool
             ^Callable #(a/>!! ch (a/<!! (send-request* client op-map))))
    ch))
