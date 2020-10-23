;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.flow.util
  "Impl, don't call directly."
  (:require [clojure.string :as str]))

(defmacro defstep [step-name args & body]
  `(def ~step-name
     {:name ~(-> step-name name (str/replace #"-" " "))
      :f (fn ~args ~@body)}))
