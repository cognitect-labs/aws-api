;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.signers
  "Impl, don't call directly."
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

  The optional `extra-chars` specifies extra characters to not encode."
  ([^String s]
   (when s
     (uri-encode s "")))
  ([^String s extra-chars]
   (when s
     (let [safe-chars (->> extra-chars
                           (into #{\_ \- \~ \.})
                           (into #{} (map int)))
           builder    (StringBuilder.)]
       (doseq [b (.getBytes s "UTF-8")]
         (.append builder
                  (if (or (Character/isLetterOrDigit ^int b)
                          (contains? safe-chars b))
                    (char b)
                    (format "%%%02X" b))))
       (.toString builder)))))

(defn credential-scope
  [headers {:keys [region service]}]
  (str/join "/" [(->> (get headers "x-amz-date")
                      (util/parse-date util/x-amz-date-format)
                      (util/format-date util/x-amz-date-only-format))
                 region
                 service
                 "aws4_request"]))

(defn- canonical-method
  [request-method]
  (-> request-method name str/upper-case))

(defn- canonical-uri
  [uri]
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
  (let [qs (or query-string (second (str/split uri #"\?")))]
    (when-not (str/blank? qs)
      (->> (str/split qs #"&")
           (map #(str/split % #"=" 2))
           ;; TODO (dchelimsky 2019-01-30) decoding first because sometimes
           ;; it's already been encoding. Look into avoiding that!
           (map (fn [kv] (map #(uri-encode (URLDecoder/decode %)) kv)))
           (sort (fn [[k1 v1] [k2 v2]]
                   (if (= k1 k2)
                     (compare v1 v2)
                     (compare k1 k2))))
           (map (fn [[k v]] (str k "=" v)))
           (str/join "&")))))

(defn- canonical-headers-string
  [headers]
  (->> headers
       (map (fn [[k v]] (str k ":" v "\n")))
       (str/join "")))

(defn signed-headers [headers] (str/join ";" (keys headers)))

(def hashed-body (comp util/hex-encode util/sha-256))

(defn canonical-request
  [{:keys [request-method uri headers body] :as request}]
  (str/join "\n" [(canonical-method request-method)
                  (canonical-uri uri)
                  (canonical-query-string request)
                  (canonical-headers-string headers)
                  (signed-headers headers)
                  (or (get headers "x-amz-content-sha256")
                      (hashed-body body))]))

(defn string-to-sign
  [{:keys [headers] :as request} auth-info]
  (let [bytes (.getBytes ^String (canonical-request request))]
    (str/join "\n" ["AWS4-HMAC-SHA256"
                    (get headers "x-amz-date")
                    (credential-scope headers auth-info)
                    (util/hex-encode (util/sha-256 bytes))])))

(defn signing-key
  [{:keys [headers]} {:keys [secret-access-key region service]}]
  (-> (.getBytes (str "AWS4" secret-access-key) "UTF-8")
      (util/hmac-sha-256 (->> (get headers "x-amz-date")
                              (util/parse-date util/x-amz-date-format)
                              (util/format-date util/x-amz-date-only-format)))
      (util/hmac-sha-256 region)
      (util/hmac-sha-256 service)
      (util/hmac-sha-256 "aws4_request")))

(defn signature
  [request auth-info]
  (util/hex-encode
   (util/hmac-sha-256 (signing-key request auth-info)
                      (string-to-sign request auth-info))))

(defn auth-info [service endpoint {:keys [:aws/access-key-id :aws/secret-access-key :aws/session-token]}]
  {:access-key-id     access-key-id
   :secret-access-key secret-access-key
   :session-token     session-token
   :service           (or (service/signing-name service)
                          (service/endpoint-prefix service))
   :region            (or (get-in endpoint [:credentialScope :region])
                          (get-in endpoint [:region]))})

(defn auth-params [op timeout {:keys [headers] :as request} auth-info]
  ;; See https://docs.aws.amazon.com/general/latest/gr/sigv4-add-signature-to-request.html
  ;; See https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html
  {:Action              op ;; e.g. "ListBuckets"
   :X-Amz-Algorithm     "AWS4-HMAC-SHA256"
   :X-Amz-Credential    (format "%s/%s" (:access-key-id auth-info) (credential-scope headers auth-info))
   :X-Amz-Date          (->> (get headers "x-amz-date")
                             (util/parse-date util/x-amz-date-format)
                             (util/format-date util/x-amz-date-only-format))
   :X-Amz-Expires       timeout ;; seconds
   :X-Amz-SignedHeaders (signed-headers headers)
   :X-Amz-Signature     (signature request auth-info)})

(defn authorization-string [{:keys [X-Amz-Credential
                                    X-Amz-SignedHeaders
                                    X-Amz-Signature]}]
  (format "AWS4-HMAC-SHA256 Credential=%s, SignedHeaders=%s, Signature=%s"
          X-Amz-Credential
          X-Amz-SignedHeaders
          X-Amz-Signature))

(defn- normalize-headers
  [headers]
  (reduce-kv (fn [m k v]
               (assoc m (str/lower-case k) (-> v str/trim (str/replace  #"\s+" " "))))
             (sorted-map)
             headers))

(defn presigned-url [op timeout service endpoint credentials http-request & {:keys [content-sha256-header?]}]
  )

(defn v4-sign-http-request
  [service endpoint credentials http-request & {:keys [content-sha256-header?]}]
  (let [auth-info (auth-info service endpoint credentials)
        req       (update http-request :headers normalize-headers)]
    (update req :headers
            #(-> %
                 (assoc "authorization" (authorization-string (auth-params "noop" 0 req auth-info)))
                 (cond->
                     (:session-token auth-info)
                   (assoc "x-amz-security-token" (:session-token auth-info))
                   content-sha256-header?
                   (assoc "x-amz-content-sha256" (hashed-body (:body req))))))))

(defmethod client/sign-http-request "v4"
  [service endpoint credentials http-request]
  (v4-sign-http-request service endpoint credentials http-request))

(defmethod client/sign-http-request "s3"
  [service endpoint credentials http-request]
  (v4-sign-http-request service endpoint credentials http-request :content-sha256-header? true))

(defmethod client/sign-http-request "s3v4"
  [service endpoint credentials http-request]
  (v4-sign-http-request service endpoint credentials http-request :content-sha256-header? true))
