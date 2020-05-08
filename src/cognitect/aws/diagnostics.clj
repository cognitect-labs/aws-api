;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.diagnostics
  "Experimental. Don't count on it."
  (:require [cognitect.aws.flow :as flow]))

(set! *warn-on-reflection* true)

(defn log
  ([result]
   (log result
                  (fn [entry]
                    (-> entry
                        (update :input dissoc :service)
                        (update :output dissoc :service)))))
  ([result fltr]
   (->> (::flow/log (meta result))
        (map fltr))))

(defn summarize-log
  [result]
  (->> result
       log
       (mapv #(select-keys % [:name :ms]))))

(defn trace-key [result k]
  (->> result
       (log identity)
       (map #(update % :input select-keys [k]))
       (map #(update % :output select-keys [k]))
       (remove #(= (:input %) (:output %)))
       (map #(select-keys % [:name :input :output]))
       (filterv (comp (complement empty?) :output))))
