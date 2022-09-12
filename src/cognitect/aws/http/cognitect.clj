;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.http.cognitect
  (:require [cognitect.http-client :as impl]
            [cognitect.aws.http :as aws]))

(set! *warn-on-reflection* true)

(defn create
  []
  (let [c (impl/create {})]
    (reify aws/HttpClient
      (-submit [_ request channel]
        (impl/submit c request channel))
      (-stop [_]
        (impl/stop c)))))
