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

(defn default-uri-encode
  "Escape (%XX) special characters in the string `s`.

  Letters, digits, and the characters `_-~.` are never encoded.

  The optional `extra-chars` specifies extra characters to not encode."
  ([^String s]
   (when s
     (default-uri-encode s "")))
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

(defn uri-encode-twice
  ([^String s]
   (when s
     (uri-encode-twice s "")))
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
  [{:keys [uri]} & {:keys [uri-encode]}]
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
           (map (fn [kv] (map #(default-uri-encode (URLDecoder/decode %)) kv)))
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
  [{:keys [headers body content-length] :as request} & {:keys [uri-encode]}]
  (str/join "\n" [(canonical-method request)
                  (canonical-uri request :uri-encode uri-encode)
                  (canonical-query-string request)
                  (canonical-headers-string request)
                  (signed-headers request)
                  (or (get headers "x-amz-content-sha256")
                      (hashed-body request))]))

(defn string-to-sign
  [request auth-info & {:keys [uri-encode]}]
  (let [bytes (.getBytes ^String (canonical-request request :uri-encode uri-encode))]
    (str/join "\n" ["AWS4-HMAC-SHA256"
                    (get-in request [:headers "x-amz-date"])
                    (credential-scope auth-info request)
                    (util/hex-encode (util/sha-256 bytes))])))

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
  [auth-info request & {:keys [uri-encode]}]
  (util/hex-encode
   (util/hmac-sha-256 (signing-key request auth-info)
                      (string-to-sign request auth-info :uri-encode uri-encode))))

(defn v4-sign-http-request
  [service endpoint credentials http-request & {:keys [content-sha256-header? uri-encode]}]
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
                      (signature auth-info req :uri-encode uri-encode)))))

(defmethod client/sign-http-request "v4"
  [service endpoint credentials http-request]
  (v4-sign-http-request service endpoint credentials http-request :uri-encode uri-encode-twice))

(defmethod client/sign-http-request "s3"
  [service endpoint credentials http-request]
  (v4-sign-http-request service endpoint credentials http-request :content-sha256-header? true :uri-encode default-uri-encode))

(defmethod client/sign-http-request "s3v4"
  [service endpoint credentials http-request]
  (v4-sign-http-request service endpoint credentials http-request :content-sha256-header? true :uri-encode uri-encode-twice))

(comment

  (canonical-uri {:uri "/2015-03-31/functions/arn:aws:lambda:us-east-1:068723471644:function:test/invocations"}
                 :uri-encode default-uri-encode)

  "/2015-03-31/functions/arn%3Aaws%3Alambda%3Aus-east-1%3A068723471644%3Afunction%3Atest/invocations"

  (canonical-uri {:uri "/has space/"}
                 :uri-encode default-uri-encode)

  "/has%20space/"

  (canonical-uri {:uri "/ሴ"}
                 :uri-encode default-uri-encode)

  "/%E1%88%B4"
)
