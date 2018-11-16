(ns cognitect.aws.helpers)

(defn capped-exponential-backoff
  "Returns a function of the num-retries (so far), which returns the
  lesser of max-backoff and an exponentially increasing multiple of
  base, or nil when (> num-retries max-retries).
  See cogniect.aws.client.api/client to see how it is used."
  [base max-backoff max-retries]
  (fn [num-retries]
    (when (<= num-retries max-retries)
      (min max-backoff
           (* base (bit-shift-left 1 num-retries))))))
