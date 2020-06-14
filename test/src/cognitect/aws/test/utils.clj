;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.test.utils
  (:require [clojure.string :as str]))

(defn stub-getenv [env]
  (fn
    ([] env)
    ([k] (get env k))))

(defn stub-getProperty [props]
  (fn [k]
    (get props k)))

(defn query-string->vec [s]
  (->> (str/split s #"&")
       (map #(str/split % #"="))))

(defn query-string->map [s]
  (->> (query-string->vec s)
       (map (fn [[a b]] [a b]))
       (into {})))
