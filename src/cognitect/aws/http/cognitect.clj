(ns cognitect.aws.http.cognitect
  (:require [cognitect.http-client :as impl]
            [cognitect.aws.http :as aws]))

(defn create
  []
  (let [c (impl/create {:trust-all true})]  ;; FIX :trust-all
    (reify aws/HttpClient
      (-submit [_ request channel]
        (impl/submit c request channel))
      (-stop [_]
        (impl/stop c)))))
