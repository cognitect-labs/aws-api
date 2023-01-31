;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.test.utils
  (:import (java.nio ByteBuffer)))

(defn stub-getenv [env]
  (fn
    ([] env)
    ([k] (get env k))))

(defn stub-getProperty [props]
  (fn [k]
    (get props k)))

(defn ^:private bbuf->bytes
  [^ByteBuffer bbuf]
  (when bbuf
    (let [bytes (byte-array (.remaining bbuf))]
      (.get (.duplicate bbuf) bytes)
      bytes)))

(defn bbuf->str
  "Creates a string from java.nio.ByteBuffer object.
   The encoding is fixed to UTF-8."
  [^ByteBuffer bbuf]
  (when-let [bytes (bbuf->bytes bbuf)]
    (String. ^bytes bytes "UTF-8")))
