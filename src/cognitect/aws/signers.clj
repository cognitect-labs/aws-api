;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.signers
  "Impl, don't call directly."
  (:require [clojure.string :as str]
            [cognitect.aws.service :as service]
            [cognitect.aws.util :as util])
  (:import [java.net URI]
           [java.net URLDecoder]))

(set! *warn-on-reflection* true)

(defmulti sign-http-request
  "Sign the HTTP request."
  (fn [service _endpoint _credentials _http-request]
    (get-in service [:metadata :signatureVersion])))

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
  [{:keys [region service]} request]
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
  [{:keys [uri]} {:keys [double-encode? normalize-uri?]}]
  (let [[path _query] (str/split uri #"\?")
        ^String encoded-path (-> path
                                 (cond-> double-encode? (uri-encode "/"))
                                 (str/replace #"^//+" "/") ; (URI.) throws Exception on '//' at beginning of string.
                                 (str/replace #"\s" "%20"); (URI.) throws Exception on space.
                                 (URI.)
                                 (cond-> normalize-uri? (.normalize))
                                 (.getPath)
                                 (uri-encode "/"))]
    (cond
      (.isEmpty encoded-path)
      "/"

      ;; https://github.com/aws/aws-sdk-java/blob/fd409de/aws-java-sdk-core/src/main/java/com/amazonaws/auth/AbstractAWSSigner.java#L392-L397
      ;; Normalization can leave a trailing slash at the end of the resource path,
      ;; even if the input path doesn't end with one. Example input: /foo/bar/.
      ;; Remove the trailing slash if the input path doesn't end with one.
      (and (not= encoded-path "/")
           (str/ends-with? encoded-path "/")
           (not (str/ends-with? path "/")))
      (.substring encoded-path 0 (dec (.length encoded-path)))

      :else encoded-path)))

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

(defn hashed-body
  [request]
  (util/hex-encode (util/sha-256 (:body request))))

(defn canonical-request
  [{:keys [headers] :as request} opts]
  (str/join "\n" [(canonical-method request)
                  (canonical-uri request opts)
                  (canonical-query-string request)
                  (canonical-headers-string request)
                  (signed-headers request)
                  (or (get headers "x-amz-content-sha256")
                      (hashed-body request))]))

(defn string-to-sign
  [request auth-info opts]
  (let [bytes (.getBytes ^String (canonical-request request opts))]
    (str/join "\n" ["AWS4-HMAC-SHA256"
                    (get-in request [:headers "x-amz-date"])
                    (credential-scope auth-info request)
                    (util/hex-encode (util/sha-256 bytes))])))

(defn signing-key
  [request {:keys [secret-access-key region service]}]
  (-> (.getBytes (str "AWS4" secret-access-key) "UTF-8")
      (util/hmac-sha-256 (->> (get-in request [:headers "x-amz-date"])
                              (util/parse-date util/x-amz-date-format)
                              (util/format-date util/x-amz-date-only-format)))
      (util/hmac-sha-256 region)
      (util/hmac-sha-256 service)
      (util/hmac-sha-256 "aws4_request")))

(defn signature
  [auth-info request opts]
  (util/hex-encode
   (util/hmac-sha-256 (signing-key request auth-info)
                      (string-to-sign request auth-info opts))))

(defn v4-sign-http-request
  [service endpoint credentials http-request & {:keys [content-sha256-header? double-url-encode? normalize-uri-paths?]}]
  (let [{:keys [:aws/access-key-id :aws/secret-access-key :aws/session-token]} credentials
        auth-info      {:access-key-id     access-key-id
                        :secret-access-key secret-access-key
                        :service           (or (service/signing-name service)
                                               (service/endpoint-prefix service))
                        :region            (or (get-in endpoint [:credentialScope :region])
                                               (:region endpoint))}
        req (cond-> http-request
              session-token          (assoc-in [:headers "x-amz-security-token"] session-token)
              content-sha256-header? (assoc-in [:headers "x-amz-content-sha256"] (hashed-body http-request)))]
    (assoc-in req
              [:headers "authorization"]
              (format "AWS4-HMAC-SHA256 Credential=%s/%s, SignedHeaders=%s, Signature=%s"
                      (:access-key-id auth-info)
                      (credential-scope auth-info req)
                      (signed-headers req)
                      (signature auth-info req {:double-encode? double-url-encode?
                                                :normalize-uri? normalize-uri-paths?})))))

(defn bearer-sign-http-request
  [service endpoint {:keys [token]} http-request & {:keys [content-sha256-header? double-url-encode? normalize-uri-paths?]}] 
  (assoc-in http-request [:headers "authorization"] (str "Bearer " token)))

;; https://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html
;;
;; Each path segment must be URI-encoded twice (except for Amazon S3 which only gets URI-encoded once).
;;
;; Normalize URI paths according to RFC 3986.
;; In exception to this, you do not normalize URI paths for requests to Amazon S3
(defmethod sign-http-request "v4"
  [service endpoint credentials http-request]
  (v4-sign-http-request service endpoint credentials http-request
                        :double-url-encode? true
                        :normalize-uri-paths? true))

(defmethod sign-http-request "bearer"
  [service endpoint credentials http-request]
  (bearer-sign-http-request service endpoint credentials http-request
                            :double-url-encode? true
                            :normalize-uri-paths? true))

(defmethod sign-http-request "s3"
  [service endpoint credentials http-request]
  (v4-sign-http-request service endpoint credentials http-request
                        :content-sha256-header? true))

(defmethod sign-http-request "s3v4"
  [service endpoint credentials http-request]
  (v4-sign-http-request service endpoint credentials http-request
                        :content-sha256-header? true))
