(ns cognitect.aws.retry
  (:require [clojure.core.async :as a]))

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

(defn capped-exponential-backoff
  "Returns a function of the num-retries (so far), which returns the
  lesser of max-backoff and an exponentially increasing multiple of
  base, or nil when (>= num-retries max-retries).
  See with-retry to see how it is used."
  [base max-backoff max-retries]
  (fn [num-retries]
    (when (< num-retries max-retries)
      (min max-backoff
           (* base (bit-shift-left 1 num-retries))))))

(def default-backoff (capped-exponential-backoff 100 20000 3))

(def default-retriable?
  "A fn of http-response which returns true if http-response contains
  a cognitect.anomalies/category of :cognitect.anomalies/busy or
  :cognitect.anomalies/unavailable"
  (fn [http-response]
    (contains? #{:cognitect.anomalies/busy
                 :cognitect.anomalies/unavailable}
               (:cognitect.anomalies/category http-response))))
