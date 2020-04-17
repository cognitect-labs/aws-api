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

(defn signed-headers [headers]
  (str/join ";" (keys headers)))

(def hashed-body (comp util/hex-encode util/sha-256))

(defn canonical-request*
  [{:keys [request-method uri headers] :as request}
   {:keys [body signed-headers-string canonical-headers-string]}]
  (str/join "\n" [(canonical-method request-method)
                  (canonical-uri uri)
                  (canonical-query-string request)
                  canonical-headers-string
                  signed-headers-string
                  body]))

(defn canonical-request
  [{:keys [headers body] :as request}]
  (canonical-request* request
                      {:body                     (or (get headers "x-amz-content-sha256")
                                                     (hashed-body body))
                       :signed-headers-string    (signed-headers headers)
                       :canonical-headers-string (canonical-headers-string headers)}))

(defn presign-canonical-request
  [{:keys [headers] :as request}]
  (canonical-request* request
                      {:body                     "UNSIGNED-PAYLOAD"
                       :signed-headers-string    (signed-headers (dissoc headers "x-amz-date"))
                       :canonical-headers-string
                       (canonical-headers-string (dissoc headers "x-amz-date"))}))

(defn string-to-sign
  [{:keys [headers] :as request} auth-info]
  (let [bytes (.getBytes ^String (canonical-request request))]
    (str/join "\n" ["AWS4-HMAC-SHA256"
                    (get headers "x-amz-date")
                    (credential-scope headers auth-info)
                    (util/hex-encode (util/sha-256 bytes))])))

(defn presign-string-to-sign
  [{:keys [headers] :as request} auth-info canonical-request]
  (let [bytes (.getBytes ^String canonical-request)]
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
  {;; :Action              (name op) ;; e.g. "ListBuckets"
   :X-Amz-Algorithm     "AWS4-HMAC-SHA256"
   :X-Amz-Credential    (format "%s/%s" (:access-key-id auth-info) (credential-scope headers auth-info))
   :X-Amz-Date          (get headers "x-amz-date")
   :X-Amz-Expires       timeout ;; seconds
   :X-Amz-SignedHeaders (signed-headers headers)})

(defn authorization-string [{:keys [X-Amz-Credential
                                    X-Amz-SignedHeaders]}
                            signature]
  (format "AWS4-HMAC-SHA256 Credential=%s, SignedHeaders=%s, Signature=%s"
          X-Amz-Credential
          X-Amz-SignedHeaders
          signature))

(defn- normalize-headers
  [headers]
  (reduce-kv (fn [m k v]
               (assoc m (str/lower-case k) (-> v str/trim (str/replace  #"\s+" " "))))
             (sorted-map)
             headers))

(defn update-keys [m f]
  (reduce-kv (fn [m k v] (assoc m (f k) v)) {} m))

(defn format-qs [params]
  (str/join "&" (map (fn [[k v]] (str k "=" v)) params)))

(defn presign-http-request
  [http-request op timeout service endpoint credentials]
  (let [auth-info          (auth-info service endpoint credentials)
        params-without-sig (into (sorted-map)
                                 (-> (auth-params op timeout http-request auth-info)
                                     (update :X-Amz-Credential uri-encode)
                                     (assoc :X-Amz-SignedHeaders (signed-headers
                                                                  (dissoc (:headers http-request)
                                                                          "x-amz-date")))
                                     (update-keys name)))
        qs-before-sig      (format-qs params-without-sig)
        canonical-request  (presign-canonical-request (-> http-request
                                                          (assoc :query-string qs-before-sig)))
        string-to-sign     (presign-string-to-sign http-request auth-info canonical-request)
        signing-key        (signing-key http-request auth-info)
        signature          (util/hex-encode (util/hmac-sha-256 signing-key string-to-sign))
        params-with-sig    (assoc params-without-sig "X-Amz-Signature" signature)
        qs-with-sig        (format-qs params-with-sig)]
    (-> http-request
        (update :headers dissoc "x-amz-date")
        (assoc :query-string qs-with-sig
               :presigned-request-meta
               {:canonical-request canonical-request
                :string-to-sign string-to-sign}))))

(comment
  ;; test from https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html

  (let [AWSAccessKeyId "AKIAIOSFODNN7EXAMPLE"
        AWSSecretAccessKey "AWSSecretAccessKey"
        X-Amz-Date "20130524T000000Z"
        X-Amz-Expires 86400
        X-Amz-SignedHeaders "host"]
    (presign-http-request
     {:request-method :get
      :scheme :https
      :server-port 443
      :uri "/test.txt"
      :headers {"x-amz-date" X-Amz-Date "host" "examplebucket.s3.amazonaws.com"}
      :server-name "amazonaws.com"
      :body nil}
     nil ;; op
     X-Amz-Expires
     {:metadata {:signingName "s3"}}
     {:region "us-east-1"}
     {:aws/access-key-id AWSAccessKeyId
      :aws/secret-access-key AWSSecretAccessKey}
     ))

  ;; canonical-request should be
"GET\n/test.txt\nX-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host\nhost:examplebucket.s3.amazonaws.com\n\nhost\nUNSIGNED-PAYLOAD"

;; is
"GET\n/test.txt\nX-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host\nhost:examplebucket.s3.amazonaws.com\n\nhost\nUNSIGNED-PAYLOAD"



  ;; string to sign should be
  "AWS4-HMAC-SHA256\n20130524T000000Z\n20130524/us-east-1/s3/aws4_request\n3bfa292879f6447bbcda7001decf97f4a54dc650c8942174ae0a9121cf58ad04"
  ;; is
  "AWS4-HMAC-SHA256\n20130524T000000Z\n20130524/us-east-1/s3/aws4_request\n3bfa292879f6447bbcda7001decf97f4a54dc650c8942174ae0a9121cf58ad04"


  ;; query-string should be
  "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404"
  ;; is
  "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-Signature=319dde919b23ca1e8e1b3a8e0d6faffc5c830c6785a7ec7a9c9eaef4f409aeb7&X-Amz-SignedHeaders=host"


  )

(defn v4-sign-http-request
  [service endpoint credentials http-request & {:keys [content-sha256-header?]}]
  (let [auth-info (auth-info service endpoint credentials)
        req       (update http-request :headers normalize-headers)]
    (update req :headers
            #(-> %
                 (assoc "authorization" (authorization-string (auth-params "noop" 0 req auth-info)
                                                              (signature req auth-info)))
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

(defmethod client/presign-http-request* :default
  [context]
  (let [{:keys [http-request op timeout service endpoint
                credentials]} context]
    (presign-http-request http-request op timeout service endpoint credentials)))

