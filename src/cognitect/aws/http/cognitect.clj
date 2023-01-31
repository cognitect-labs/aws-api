;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.http.cognitect
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [cognitect.http-client :as impl]
            [cognitect.aws.http :as aws]
            [cognitect.aws.util :as aws.util])
  (:import (java.io InputStream)
           (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)

(defn ^:private bbuf->bytes
  [^ByteBuffer bbuf]
  (when bbuf
    (let [bytes (byte-array (.remaining bbuf))]
      (.get (.duplicate bbuf) bytes)
      bytes)))

(defn ^:private bbuf->input-stream
  "Creates a Java.io.InputStream from java.nio.ByteBuffer object."
  [^ByteBuffer bbuf]
  (when bbuf
    (io/input-stream (bbuf->bytes bbuf))))

(defprotocol BBuffable
  (->bbuf [data]))

(extend-protocol BBuffable
  (Class/forName "[B")
  (->bbuf [bs] (ByteBuffer/wrap bs))

  String
  (->bbuf [s] (->bbuf (.getBytes s "UTF-8")))

  InputStream
  (->bbuf [is] (->bbuf (aws.util/->byte-array is)))

  nil
  (->bbuf [_]))

(defn ^:private handle-response
  [initial-response-chan
   parsed-response-chan]
  (a/pipeline 1
              parsed-response-chan
              (map (fn [response] (update response :body bbuf->input-stream)))
              initial-response-chan)
  parsed-response-chan)

(defn create
  []
  (let [client (impl/create {})]
    (reify aws/HttpClient
      (-submit [_ request channel]
        (handle-response
          (impl/submit client
                       (update request :body ->bbuf))
          channel))
      (-stop [_]
        (impl/stop client)))))
