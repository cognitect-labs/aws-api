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
  [{:keys [region service-name]} date]
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
      (str/replace #"//+" "/") ; (URI.) throws Exception on '//'.
      (str/replace #"\s" "%20"); (URI.) throws Exception on space.
      (URI.)
      (.normalize)
      (.getPath)
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
  [{:keys [signing-params canonical-request] :as context}]
  (let [{:keys [amz-date]} signing-params
        bytes              (.getBytes ^String canonical-request)]
    (assoc context
           :string-to-sign
           (str/join "\n" ["AWS4-HMAC-SHA256"
                           amz-date
                           (credential-scope signing-params amz-date)
                           (util/hex-encode (util/sha-256 bytes))]))))

(defn- add-signing-key
  [{{:keys [secret-access-key region service-name amz-date]} :signing-params :as context}]
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
  [{{:keys [request-method uri headers body] :as request} :req :as context}]
  (assoc context
         :canonical-request
         (str/join "\n" [(canonical-method request-method)
                         (canonical-uri uri (:uri-encoder context))
                         (canonical-query-string request)
                         (canonical-headers-string headers)
                         (signed-headers-string headers)
                         body])))

(defn- authorization-string [{:keys [algo credential signed-headers]} signature]
  (format "%s Credential=%s, SignedHeaders=%s, Signature=%s"
          algo
          credential
          signed-headers
          signature))

(defn- auth-info [service endpoint {:aws/keys [access-key-id secret-access-key session-token]}]
  (cond->
      {:access-key-id     access-key-id
       :secret-access-key secret-access-key
       :service-name      (or (service/signing-name service)
                              (service/endpoint-prefix service))
       :region            (or (get-in endpoint [:credentialScope :region])
                              (get-in endpoint [:region]))}
    session-token
    (assoc :session-token session-token)))

(defn- signing-params
  [op expires {:strs [x-amz-date] :as headers-to-sign} auth-info amz-date]
  (assoc auth-info
         :op             (name op)
         :algo           "AWS4-HMAC-SHA256"
         :credential     (format "%s/%s" (:access-key-id auth-info) (credential-scope auth-info amz-date))
         :amz-date       amz-date
         :expires        expires ;; seconds
         :signed-headers (signed-headers-string headers-to-sign)))

(defn- move-uri-qs-to-qs [req]
  (let [orig-qs      (:query-string req)
        [uri uri-qs] (some-> req :uri (str/split #"\?"))
        new-qs       (not-empty (str/join "&" (remove str/blank? [orig-qs uri-qs])))]
    (cond-> (assoc req :uri uri)
      new-qs
      (assoc :query-string new-qs))))

(defn- add-session-token? [auth-info]
  (and (:session-token auth-info)
       (= "s3" (:service-name auth-info))))

(defn v4-sign-http-request
  [service endpoint credentials http-request & {:keys [content-sha256-header? uri-encoder]}]
  (let [auth-info               (auth-info service endpoint credentials)
        req                     (cond->
                                    (-> http-request
                                        (update :headers normalize-headers)
                                        move-uri-qs-to-qs)
                                  (add-session-token? auth-info)
                                  (update :headers assoc "x-amz-security-token" (:session-token auth-info)))
        amz-date                (get-in http-request [:headers "x-amz-date"])
        hashed-body             (hashed-body (:body req))
        signing-params          (signing-params "noop" 0 (:headers req) auth-info amz-date)
        {:keys [signature]
         :as   signing-context} (->> {:signing-params signing-params
                                      :uri-encoder    uri-encoder
                                      :req            (assoc req :body hashed-body)}
                                     add-canonical-request
                                     add-signing-key
                                     add-string-to-sign
                                     add-signature)
        auth-string             (authorization-string signing-params signature)]
    (with-meta (update req :headers
                       #(-> %
                            (assoc "authorization" auth-string)
                            (cond->
                                (:session-token signing-params)
                              (assoc "x-amz-security-token" (:session-token signing-params))
                              content-sha256-header?
                              (assoc "x-amz-content-sha256" hashed-body))))
      (-> signing-context
          (dissoc :req)
          (update :signing-params dissoc :secret-access-key)))))

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

(defmethod signing/presigned-url "s3"
  [{:keys             [http-request op service endpoint credentials]
    {:keys [expires]} :presigned-url}]
  (let [auth-info               (auth-info service endpoint credentials)
        req*                    (-> http-request
                                    (update :headers normalize-headers)
                                    move-uri-qs-to-qs)
        amz-date                (get-in req* [:headers "x-amz-date"])
        req                     (update req* :headers dissoc "x-amz-date")
        signing-params          (signing-params op expires (:headers req) auth-info amz-date)
        qs-params-no-sig        (into (sorted-map)
                                      (cond->
                                          (-> signing-params
                                              (select-keys [:op :algo :credential :amz-date :expires :signed-headers
                                                            :session-token])
                                              (clojure.set/rename-keys {:op             "Action"
                                                                        :algo           "X-Amz-Algorithm"
                                                                        :credential     "X-Amz-Credential"
                                                                        :amz-date       "X-Amz-Date"
                                                                        :expires        "X-Amz-Expires"
                                                                        :session-token  "X-Amz-Security-Token"
                                                                        :signed-headers "X-Amz-SignedHeaders"})
                                              (update "X-Amz-Credential" util/uri-encode)
                                              (update "X-Amz-Date" util/uri-encode))
                                        (:session-token signing-params)
                                        (update "X-Amz-Security-Token" util/uri-encode)))
        qs-no-sig               (util/query-string qs-params-no-sig)
        {:keys [signature]
         :as   signing-context} (->> {:signing-params signing-params
                                      :uri-encoder    s3-uri-encoder
                                      :req            (assoc req
                                                             :query-string qs-no-sig
                                                             :body "UNSIGNED-PAYLOAD")}
                                     add-canonical-request
                                     add-signing-key
                                     add-string-to-sign
                                     add-signature)
        qs-params-with-sig      (assoc qs-params-no-sig "X-Amz-Signature" signature)
        qs-with-sig             (util/query-string qs-params-with-sig)]
    {:presigned-url               (str "https://" (:server-name req) (:uri req) "?" qs-with-sig)
     :cognitect.aws.signing/basis (-> signing-context
                                      (dissoc :req)
                                      (update :signing-params assoc :secret-access-key "**REDACTED**"))}))
