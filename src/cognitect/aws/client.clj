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
  "Call req-fn, a function that wraps some operation and returns a
  channel. Uses retry? on the response to decide whether the request
  should be retried. Uses backoff to decide how much to backoff.  When
  retry? returns false, puts response on resp-chan."
  [req-fn resp-chan retry? backoff]
  (a/go-loop [retries 0]
    (when-not (zero? retries)
      (a/<! (a/timeout (backoff retries))))
    (let [resp (a/<! (req-fn))]
      (if (retry? resp)
        (recur (inc retries))
        (a/>! resp-chan resp))))
  resp-chan)

(defn default-retry?
  [http-response]
  (when-let [anomaly-category (:cognitect.anomalies/category http-response)]
    (contains? #{:cognitect.anomalies/busy
                 :cognitect.anomalies/unavailable}
               anomaly-category)))

(defn capped-exponential-backoff
  ;; TODO: (dchelimsky 2018-07-27) add docstring, please!
  [base max-backoff random-range]
  (fn [retries]
    (min max-backoff
         (* (+ base (rand-int random-range))
            (bit-shift-left 1 retries)))))

(defn stop
  "Stop the client."
  [client]
  (http/stop (:http-client client))
  (credentials/stop (:credentials client)))

(defn wait
  "Call req until the predicate success? returns true. Return a
  promise channel that will receive the value ::done when the waiter
  completes.

  Only retry when the predicate retry? returns true."
  [{:keys [req success? retry? delay max-retries]
    :or {retry? (constantly false) max-retries 25 delay 20000}}]
  (let [result-chan (a/promise-chan)]
    (a/go-loop [retries 0]
      (if (>= retries max-retries)
        (a/>! result-chan {::error ::too-many-retries})
        (do
          (when-not (zero? retries)
            (a/<! (a/timeout delay)))
          (let [result (a/<! (req))]
            (cond
              (success? result)
              (a/>! result-chan ::done)

              (or (retry? result)
                  (not (::error result)))
              (recur (inc retries))

              :else
              (a/>! result-chan {::error :waiter-state-error}))))))
    result-chan))

;; TODO: Paginator that push vector of responses in a channel
