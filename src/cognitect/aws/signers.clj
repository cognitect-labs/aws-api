;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.signers
  "Implement the request signers for the AWS services."
  (:require [clojure.string :as str]
            [cognitect.aws.client :as client]
            [cognitect.aws.service :as service]
            [cognitect.aws.util :as util])
  (:import [java.net URI]
           [java.net URLDecoder]))

(set! *warn-on-reflection* true)

(defn uri-encode
  "Escape (%XX) special characters in the string `s`.

  Letters, digits, and the characters `_-~.` are never encoded.

  The optional string `safe` specifies extra characters to not encode."
  [^String s & [safe]]
  (when s
    (let [safe-chars (->> [\_ \- \~ \.]
                          (concat (set safe))
                          (map byte)
                          set)
          builder (StringBuilder.)]
      (doseq [b (.getBytes s "UTF-8")]
        (.append builder
                 (if (or (<= (byte \A) b (byte \Z))
                         (<= (byte \a) b (byte \z))
                         (<= (byte \0) b (byte \9))
                         (contains? safe-chars b))
                   (char b)
                   (format "%%%02X" b))))
      (.toString builder))))

(defn credential-scope
  [{:keys [region service] :as auth-info} request]
  (str/join "/" [(->> (get-in request [:headers "x-amz-date"])
                      (util/parse-date util/x-amz-date-format)
                      (util/format-date util/x-amz-date-only-format))
                 region
                 service
                 "aws4_request"]))

(defn- canonical-method
  [{:keys [request-method]}]
  (-> request-method name str/upper-case))

(defn- canonical-uri
  [{:keys [uri]}]
  (let [encoded-path (-> uri
                         (str/replace #"//+" "/") ; (URI.) throws Exception on '//'.
                         (str/replace #"\s" "%20"); (URI.) throws Exception on space.
                         (URI.)
                         (.normalize)
                         (.getPath)
                         (uri-encode "/"))]
    (if (.isEmpty ^String encoded-path)
      "/"
      encoded-path)))

(defn- canonical-query-string
  [{:keys [uri query-string]}]
  (let [query (or query-string
                  (second (str/split uri #"\?")))]
   (when-not (str/blank? query)
     (->> (str/split query #"&")
          (map #(let [[k v] (str/split % #"=" 2)]
                  ;; decode first because sometimes it's already been url encoded
                  (str (uri-encode (URLDecoder/decode k))
                       "="
                       (uri-encode (URLDecoder/decode v)))))
          sort
          (str/join "&")))))

(defn- canonical-headers
  [{:keys [headers]}]
  (reduce-kv (fn [m k v]
               (assoc m (str/lower-case k) (-> v str/trim (str/replace  #"\s+" " "))))
             (sorted-map)
             headers))

(defn- canonical-headers-string
  [request]
  (->> (canonical-headers request)
       (map (fn [[k v]] (str k ":" v "\n")))
       (str/join "")))

(defn signed-headers
  [request]
  (->> (canonical-headers request)
       keys
       (str/join ";")))

(defn hash-payload
  [{:keys [body] :as request}]
  (util/hex-encode (util/sha-256 body)))

(defn canonical-request
  [{:keys [headers body content-length] :as request}]
  (str/join "\n" [(canonical-method request)
                  (canonical-uri request)
                  (canonical-query-string request)
                  (canonical-headers-string request)
                  (signed-headers request)
                  (or (get headers "x-amz-content-sha256")
                      (hash-payload request))]))

(defn string-to-sign
  [request auth-info]
  (let [bytes (.getBytes ^String (canonical-request request))]
    (str/join "\n" ["AWS4-HMAC-SHA256"
                    (get-in request [:headers "x-amz-date"])
                    (credential-scope auth-info request)
                    (util/hex-encode (util/sha-256 bytes (alength bytes)))])))

(defn signing-key
  [request {:keys [secret-access-key region service] :as auth-info}]
  (-> (.getBytes (str "AWS4" secret-access-key) "UTF-8")
      (util/hmac-sha-256 (->> (get-in request [:headers "x-amz-date"])
                              (util/parse-date util/x-amz-date-format)
                              (util/format-date util/x-amz-date-only-format)))
      (util/hmac-sha-256 region)
      (util/hmac-sha-256 service)
      (util/hmac-sha-256 "aws4_request")))

(defn signature
  [auth-info request]
  (util/hex-encode
   (util/hmac-sha-256 (signing-key request auth-info)
                      (string-to-sign request auth-info))))

(defn v4-sign-http-request
  ([service region http-request credentials]
   (v4-sign-http-request service region http-request credentials false))
  ([service region http-request credentials content-sha256?]
   (let [{:keys [:aws/access-key-id :aws/secret-access-key]} credentials
         auth-info {:access-key-id access-key-id
                    :secret-access-key secret-access-key
                    :service (service/endpoint-prefix service)
                    :region (name region)}]
     (update http-request :headers
             #(cond-> %
                content-sha256? (assoc "x-amz-content-sha256" (hash-payload http-request))
                :always (assoc "authorization" (format "AWS4-HMAC-SHA256 Credential=%s/%s, SignedHeaders=%s, Signature=%s"
                                                       (:access-key-id auth-info)
                                                       (credential-scope auth-info http-request)
                                                       (signed-headers http-request)
                                                       (signature auth-info http-request))))))))

(defmethod client/sign-http-request "v4"
  [service region http-request credentials]
  (v4-sign-http-request service region http-request credentials))

(defmethod client/sign-http-request "s3"
  [service region http-request credentials]
  (v4-sign-http-request service region http-request credentials true))
