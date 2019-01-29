;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.util
  "Impl, don't call directly."
  (:require [clojure.string :as str]
            [clojure.xml :as xml]
            [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.text SimpleDateFormat]
           [java.util Date TimeZone]
           [java.util UUID]
           [java.io InputStream]
           [java.security MessageDigest]
           [org.apache.commons.codec.binary Hex]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.nio ByteBuffer]
           [java.io ByteArrayInputStream]
           [java.net URLEncoder]
           [org.apache.commons.codec.binary Base64]))

(set! *warn-on-reflection* true)

(defn ^ThreadLocal date-format
  "Return a thread-safe GMT date format that can be used with `format-date` and `parse-date`.

  See http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4228335"
  [^String fmt]
  (proxy [ThreadLocal] []
    (initialValue []
      (doto (SimpleDateFormat. fmt)
        (.setTimeZone (TimeZone/getTimeZone "GMT"))))))

(defn format-date
  ([fmt]
   (format-date fmt (Date.)))
  ([^ThreadLocal fmt inst]
   (.format ^SimpleDateFormat (.get fmt) inst)))

(defn format-timestamp
  "Format a timestamp in milliseconds."
  [inst]
  (str (long (/ (.getTime ^Date inst) 1000))))

(defn parse-date
  [^ThreadLocal fmt s]
  (.parse ^SimpleDateFormat (.get fmt) s))

(def ^ThreadLocal x-amz-date-format
  (date-format "yyyyMMdd'T'HHmmss'Z'"))

(def ^ThreadLocal x-amz-date-only-format
  (date-format "yyyyMMdd"))

(def ^ThreadLocal iso8601-date-format
  (date-format "yyyy-MM-dd'T'HH:mm:ssXXX"))

(def ^ThreadLocal iso8601-msecs-date-format
  (date-format "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))

(def ^ThreadLocal rfc822-date-format
  (date-format "EEE, dd MMM yyyy HH:mm:ss z"))

(defn hex-encode
  [#^bytes bytes]
  (String. (Hex/encodeHex bytes true)))

(defn sha-256
  "Compute the sha-256 hash of `data`.

  `data` can be:
  * a ByteBuffer,
  * an array of bytes, then `length` is required
  * nil, return the sha-256 of the empty string."
  ([data]
   (sha-256 data nil))
  ([data length]
   (let [digest (MessageDigest/getInstance "SHA-256")]
     (if (instance? ByteBuffer data)
       (do (.update digest ^ByteBuffer data)
           (.rewind ^ByteBuffer data))
       (when data
         (.update digest data 0 length)))
     (.digest digest))))

(defn hmac-sha-256
  [key ^String data]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. key "HmacSHA256"))
    (.doFinal mac (.getBytes data "UTF-8"))))

(defn bbuf->bytes
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
    (String. #^bytes bytes "UTF-8")))

(defn bbuf->input-stream
  [^ByteBuffer bbuf]
  (when bbuf
    (io/input-stream (bbuf->bytes bbuf))))

(defn #^bytes input-stream->byte-array [is]
  (doto (byte-array (.available ^InputStream is))
    (#(.read ^InputStream is %))))

(defprotocol BBuffable
  (->bbuf [data]))

(extend-protocol BBuffable
  (class (byte-array 0))
  (->bbuf [bs] (ByteBuffer/wrap bs))

  String
  (->bbuf [s] (->bbuf (.getBytes s "UTF-8")))

  java.io.InputStream
  (->bbuf [is] (->bbuf (input-stream->byte-array is)))

  nil
  (->bbuf [_]))

(defn xml-read
  "Parse the UTF-8 XML string."
  [s]
  (xml/parse (ByteArrayInputStream. (.getBytes ^String s "UTF-8"))))

(defn xml->map [element]
  (cond
    (nil? element)        nil
    (string? element)     element
    (sequential? element) (if (> (count element) 1)
                            (reduce into {} (map xml->map element))
                            (xml->map  (first element)))
    (map? element)
    (cond
      (empty? element) {}
      (:attrs element) {(:tag element)                                (xml->map (:content element))
                        (keyword (str (name (:tag element)) "Attrs")) (:attrs element)}
      :else            {(:tag element) (xml->map  (:content element))})
    :else                 nil))

(defn error-code [response-body]
  (let [error (some->> (tree-seq coll? #(if (map? %) (vals %) %) response-body)
                       (filter :Error)
                       first)]
    (some-> error (get-in [:Error :Code]))))

(defn xml-write
  [e]
  (if (instance? String e)
    (print e)
    (do
      (print (str "<" (name (:tag e))))
      (when (:attrs e)
        (doseq [attr (:attrs e)]
          (print (str " " (name (key attr)) "=\"" (val attr)"\""))))
      (if-not (empty? (:content e))
        (do
          (print ">")
          (doseq [c (:content e)]
            (xml-write c))
          (print (str "</" (name (:tag e)) ">")))
        (print " />")))))


(defn url-encode
  "Percent encode the string to put in a URL."
  [^String s]
  (-> s
      (URLEncoder/encode "UTF-8")
      (.replace "+" "%20")))

(defn query-string
  "Create a query string from a list of parameters. Values must all be
  strings."
  [params]
  (when-not (empty? params)
    (str/join "&" (map (fn [[k v]]
                         (str (url-encode (name k))
                              "="
                              (url-encode v)))
                       params))))

(defn read-json
  "Read readable as JSON. readable can be any valid input for
  clojure.java.io/reader."
  [readable]
  (json/read-str (slurp readable) :key-fn keyword))

(defn map-vals
  "Apply f to the values with the given keys, or all values if `ks` is not specified."
  ([f m]
   (map-vals f m (keys m)))
  ([f m ks]
   (into m
         (for [[k v] (select-keys m ks)]
           [k (f v)]))))

(defprotocol Base64Encodable
  (base64-encode [data]))

(extend-protocol Base64Encodable
  (class (byte-array 0))
  (base64-encode [ba] (Base64/encodeBase64String ba))

  java.io.InputStream
  (base64-encode [is] (base64-encode (input-stream->byte-array is)))

  java.lang.String
  (base64-encode [s] (base64-encode (.getBytes s))))

(defn base64-decode
  "base64 decode a base64-encoded string to an input stream"
  [s]
  (io/input-stream (Base64/decodeBase64 ^String s)))

(defn encode-jsonvalue [data]
  (Base64/encodeBase64String (.getBytes ^String (json/write-str data))))

(defn parse-jsonvalue [data]
  (-> data
      base64-decode
      io/reader
      slurp
      (json/read-str :key-fn keyword)))

(defn gen-idempotency-token []
  (UUID/randomUUID))

(defn with-defaults
  "Given a shape and data of that shape, add defaults for the
  following required keys if they are missing or bound to nil

      :idempotencyToken"
  [shape data]
  (reduce (fn [m [member-name member-spec]]
            (cond
              (not (nil? (get data member-name)))
              m

              (:idempotencyToken member-spec)
              (assoc m member-name (gen-idempotency-token))

              :else
              m))
          (or data {})
          (:members shape)))

(defonce ^:private dynalock (Object.))

(defn dynaload
  [s]
  (let [ns (namespace s)]
    (assert ns)
    (locking dynalock
      (require (symbol ns)))
    (let [v (resolve s)]
      (if v
        @v
        (throw (RuntimeException. (str "Var " s " is not on the classpath")))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Wrappers - here to support testing with-redefs since
;;;;            we can't redef static methods

(defn getenv
  ([] (System/getenv))
  ([k] (System/getenv k)))

(defn getProperty [k]
  (System/getProperty k))
