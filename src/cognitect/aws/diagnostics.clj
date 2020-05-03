;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.diagnostics
  "Experimental. Don't count on it."
  (:require [cognitect.aws.flow :as flow]))

(set! *warn-on-reflection* true)

(defn execution-log [result]
  (-> result meta ::flow/log))

(defn summarize-log
  [result]
  (->> result
       execution-log
       (mapv #(select-keys % [:name :ms]))))

(defn trace-key [result k]
  (->> result
       execution-log
       (map #(update % :input select-keys [k]))
       (map #(update % :output select-keys [k]))
       (remove #(= (:input %) (:output %)))
       (map #(select-keys % [:name :input :output]))
       (filterv (comp (complement empty?) :output))))
