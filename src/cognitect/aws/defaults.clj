(ns cognitect.aws.defaults
  (:require [cognitect.aws.helpers :as helpers]))

(def backoff (helpers/capped-exponential-backoff 100 20000 3))

(def retriable?
  "A fn of http-response which returns true if http-response contains
  a cognitect.anomalies/category of :cognitect.anomalies/busy or
  :cognitect.anomalies/unavailable"
  (fn [http-response]
    (contains? #{:cognitect.anomalies/busy
                 :cognitect.anomalies/unavailable}
               (:cognitect.anomalies/category http-response))))
