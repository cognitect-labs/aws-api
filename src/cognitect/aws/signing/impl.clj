;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.signing.impl
  "Impl, don't call directly.

  See:

  * https://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html
  * https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html#canonical-request"
  (:require [clojure.string :as str]
            [cognitect.aws.signing :as signing]
            [cognitect.aws.service :as service]
            [cognitect.aws.util :as util])
  (:import [java.net URI]
           [java.net URLDecoder]))

(set! *warn-on-reflection* true)

(defn- credential-scope
  [region service-name date]
  (str/join "/" [(->> date
                      (util/parse-date util/x-amz-date-format)
                      (util/format-date util/x-amz-date-only-format))
                 region
                 service-name
                 "aws4_request"]))

(defn- canonical-method
  [request-method]
  (-> request-method name str/upper-case))

(defn s3-uri-encoder [path]
  (util/uri-encode path :exclude-slashes))

(defn default-uri-encoder [uri]
  (-> uri
      (str/replace #"//+" "/")  ; (URI.) throws Exception on '//'.
      (str/replace #"\s" "%20") ; (URI.) throws Exception on space.
      (URI.)
      (.normalize)
      (.getPath)                ; decodes %20 back to space
      (util/uri-encode :exclude-slashes)))

(defn- canonical-uri
  [uri uri-encoder]
  (let [encoded-uri (uri-encoder uri)]
    (if (.isEmpty ^String encoded-uri)
      "/"
      encoded-uri)))

(defn- canonical-query-string
  [{:keys [query-string]}]
  (when-not (str/blank? query-string)
    (->> (util/query-string->vec query-string)
         (sort (fn [[k1 v1] [k2 v2]]
                 (if (= k1 k2)
                   (compare v1 v2)
                   (compare k1 k2))))
         (map (fn [[k v]] (str k "=" v)))
         (str/join "&"))))

(defn- canonical-headers-string
  [headers]
  (->> headers
       (map (fn [[k v]] (str k ":" v "\n")))
       (str/join "")))

(defn- signed-headers-string [headers]
  (str/join ";" (sort (keys headers))))

(def ^:private hashed-body (comp util/hex-encode util/sha-256))

(defn- add-string-to-sign
  [{:keys [signing-params canonical-request]
    {:keys [region service-name amz-date]} :signing-params
    :as context}]
  (let [bytes (.getBytes ^String canonical-request)]
    (assoc context
           :string-to-sign
           (str/join "\n" ["AWS4-HMAC-SHA256"
                           amz-date
                           (credential-scope region service-name amz-date)
                           (util/hex-encode (util/sha-256 bytes))]))))

(defn- add-signing-key
  [{{:keys [region service-name amz-date] :aws/keys [secret-access-key]} :signing-params
    :as context}]
  (assoc context
         :signing-key
         (-> (.getBytes (str "AWS4" secret-access-key) "UTF-8")
             (util/hmac-sha-256 (->> amz-date
                                     (util/parse-date util/x-amz-date-format)
                                     (util/format-date util/x-amz-date-only-format)))
             (util/hmac-sha-256 region)
             (util/hmac-sha-256 service-name)
             (util/hmac-sha-256 "aws4_request"))))

(defn- add-signature
  [{:keys [signing-key string-to-sign] :as context}]
  (assoc context :signature
         (util/hex-encode (util/hmac-sha-256 signing-key string-to-sign))))

(defn- normalize-headers
  [headers]
  (reduce-kv (fn [m k v]
               (assoc m (str/lower-case k) (-> v str/trim (str/replace  #"\s+" " "))))
             (sorted-map)
             headers))

(defn- add-canonical-request
  [{{:keys [request-method uri headers body] :as request} :req
    :keys [hashed-body]
    :as context}]
  (assoc context
         :canonical-request
         (str/join "\n" [(canonical-method request-method)
                         (canonical-uri uri (:uri-encoder context))
                         (canonical-query-string request)
                         (canonical-headers-string headers)
                         (signed-headers-string headers)
                         (or hashed-body body)])))

(defn- authorization-string [{:keys [algo credential signed-headers]} signature]
  (format "%s Credential=%s, SignedHeaders=%s, Signature=%s"
          algo
          credential
          signed-headers
          signature))

(defn- signing-params
  [service endpoint credentials op expires {:strs [x-amz-date] :as headers-to-sign} amz-date]
  (let [service-name (or (service/signing-name service)
                         (service/endpoint-prefix service))
        region       (or (get-in endpoint [:credentialScope :region])
                         (get-in endpoint [:region]))]
    (assoc credentials
           :service-name   service-name
           :region         region
           :op             (name op)
           :algo           "AWS4-HMAC-SHA256"
           :credential     (format "%s/%s" (:aws/access-key-id credentials)
                                   (credential-scope region service-name amz-date))
           :amz-date       amz-date
           :expires        expires ;; seconds
           :signed-headers (signed-headers-string headers-to-sign))))

(defn format-request [http-request]
  (let [orig-qs      (:query-string http-request)
        [uri uri-qs] (some-> http-request :uri (str/split #"\?"))
        new-qs       (not-empty (str/join "&" (remove str/blank? [orig-qs uri-qs])))]
    (-> http-request
        (assoc  :uri uri)
        (update :headers normalize-headers)
        (cond-> new-qs (assoc :query-string new-qs)))))

(defn add-signing-params [{:keys [service endpoint credentials op req amz-date]
                           {:keys [expires]} :presigned-url
                           :as context}]
  (assoc context
         :signing-params
         (signing-params service endpoint credentials op expires (:headers req)
                         amz-date)))

(defn extract-amz-date [{:keys [req] :as context}]
  (-> context
      (assoc :amz-date (get-in req [:headers "x-amz-date"]))))

(defn dissoc-amz-date [{:keys [req] :as context}]
  (-> context
      (update-in [:req :headers] dissoc "x-amz-date")))

(defn prepare-request [{:keys [credentials content-sha256-header? hashed-body] :as context}]
  (-> context
      (clojure.set/rename-keys {:http-request :req})
      (update :req format-request)
      (cond-> (:aws/session-token credentials)
        (update-in [:req :headers] assoc "x-amz-security-token" (:aws/session-token credentials)))
      (cond-> content-sha256-header?
        (update-in [:req :headers] assoc "x-amz-content-sha256" hashed-body))))

(defn add-hashed-body [context]
  (assoc context :hashed-body (hashed-body (get-in context [:http-request :body]))))

(defn add-auth-header [{:keys [signing-params signature] :as context}]
  (assoc-in context [:req :headers "authorization"]
            (authorization-string signing-params signature)))

(defn extract-req [{:keys [req] :as context}]
  (with-meta req (dissoc context :req)))

(defn redact-secret-access-key [context]
  (-> context
      (assoc-in [:credentials :aws/secret-access-key] "**REDACTED**")
      (assoc-in [:signing-params :aws/secret-access-key] "**REDACTED**")))

(defn v4-sign-http-request
  [service endpoint credentials http-request & {:keys [content-sha256-header? uri-encoder]}]
  (->> {:uri-encoder    uri-encoder
        :http-request http-request
        :op "noop"
        :service service
        :endpoint endpoint
        :credentials credentials
        :content-sha256-header? content-sha256-header?}
       add-hashed-body
       prepare-request
       extract-amz-date
       add-signing-params
       add-canonical-request
       add-signing-key
       add-string-to-sign
       add-signature
       add-auth-header
       redact-secret-access-key
       extract-req))

(defmethod signing/sign-http-request "s3"
  [service endpoint credentials http-request]
  (v4-sign-http-request service endpoint credentials http-request
                        :content-sha256-header? true
                        :uri-encoder s3-uri-encoder))

(defmethod signing/sign-http-request "v4"
  [service endpoint credentials http-request]
  (v4-sign-http-request service endpoint credentials http-request
                        :uri-encoder default-uri-encoder))

;; The only service that uses s3v4 is s3-control. aws-sdk-js assigns the v4 signer for this
(defmethod signing/sign-http-request "s3v4"
  [service endpoint credentials http-request]
  (v4-sign-http-request service endpoint credentials http-request
                        :uri-encoder default-uri-encoder))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; PRESIGN

(defn add-qs-params [{:keys [signing-params] :as context}]
  (assoc context
         :qs-params
         (into (sorted-map)
               (-> signing-params
                   (select-keys [:op :algo :credential :amz-date :expires :signed-headers
                                 :aws/session-token])
                   (clojure.set/rename-keys {:op             "Action"
                                             :algo           "X-Amz-Algorithm"
                                             :credential     "X-Amz-Credential"
                                             :amz-date       "X-Amz-Date"
                                             :expires        "X-Amz-Expires"
                                             :aws/session-token  "X-Amz-Security-Token"
                                             :signed-headers "X-Amz-SignedHeaders"})
                   (update "X-Amz-Credential" util/uri-encode)
                   (update "X-Amz-Date" util/uri-encode)
                   (cond-> (:aws/session-token signing-params)
                     (update "X-Amz-Security-Token" util/uri-encode))))))

(defn set-qs [{:keys [qs-params] :as context}]
  (update context :req assoc :query-string (util/query-string qs-params)))

(defn add-presigned-url [{:keys [req qs-params signature] :as context}]
  (assoc context :presigned-url
         (str "https://" (:server-name req) (:uri req) "?"
              (util/query-string (assoc qs-params "X-Amz-Signature" signature)))))

(defn prepare-request-for-presign [{:keys [] :as context}]
  (-> context
      (clojure.set/rename-keys {:http-request :req})
      (update :req format-request)
      (update :req assoc :body "UNSIGNED-PAYLOAD")))

(defn add-s3-uri-encoder [context]
  (assoc context :uri-encoder s3-uri-encoder))

(defmethod signing/presigned-url "s3"
  [initial-context]
  (-> initial-context
      add-s3-uri-encoder
      prepare-request-for-presign
      extract-amz-date
      dissoc-amz-date
      add-signing-params
      add-qs-params
      set-qs
      add-canonical-request
      add-signing-key
      add-string-to-sign
      add-signature
      add-presigned-url
      redact-secret-access-key))
