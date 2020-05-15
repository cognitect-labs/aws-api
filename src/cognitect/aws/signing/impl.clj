;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.signing.impl
  "Impl, don't call directly."
  (:require [clojure.string :as str]
            [cognitect.aws.signing :as signing]
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
  [{:keys [region service-name amz-date]} date]
  (str/join "/" [(->> date
                      (util/parse-date util/x-amz-date-format)
                      (util/format-date util/x-amz-date-only-format))
                 region
                 service-name
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

(defn- qs->map [qs]
  (->> (str/split (or qs "") #"&")
       (map #(str/split % #"=" 2))))

(defn- canonical-query-string
  [{:keys [query-string]}]
  (when-not (str/blank? query-string)
    (->> (qs->map query-string)
         ;; TODO (dchelimsky 2019-01-30) decoding first because sometimes
         ;; it's already been encoded. Look into avoiding that!
         (map (fn [kv] (map #(uri-encode (URLDecoder/decode %)) kv)))
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

(defn signed-headers-string [headers]
  (str/join ";" (keys headers)))

(def hashed-body (comp util/hex-encode util/sha-256))

(defn add-string-to-sign
  [{:keys [signing-params canonical-request] :as context}]
  (let [{:keys [amz-date]} signing-params
        bytes              (.getBytes ^String canonical-request)]
    (assoc context
           :string-to-sign
           (str/join "\n" ["AWS4-HMAC-SHA256"
                           amz-date
                           (credential-scope signing-params amz-date)
                           (util/hex-encode (util/sha-256 bytes))]))))

(defn add-signing-key
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

(defn add-signature
  [{:keys [signing-key string-to-sign] :as context}]
  (assoc context :signature
         (util/hex-encode (util/hmac-sha-256 signing-key string-to-sign))))

(defn- normalize-headers
  [headers]
  (reduce-kv (fn [m k v]
               (assoc m (str/lower-case k) (-> v str/trim (str/replace  #"\s+" " "))))
             (sorted-map)
             headers))

(defn format-qs [params]
  (str/join "&" (map (fn [[k v]] (str k "=" v)) params)))

(defn add-canonical-request
  [{{:keys [request-method uri headers body] :as request} :req :as context}]
  (assoc context
         :canonical-request
         (str/join "\n" [(canonical-method request-method)
                         (canonical-uri uri)
                         (canonical-query-string request)
                         (canonical-headers-string headers)
                         (signed-headers-string headers)
                         body])))

(defn authorization-string [{:keys [algo credential signed-headers]} signature]
  (format "%s Credential=%s, SignedHeaders=%s, Signature=%s"
          algo
          credential
          signed-headers
          signature))

;; TODO: collapse auth-info and auth-params into one function
(defn auth-info [service endpoint {:keys [:aws/access-key-id :aws/secret-access-key :aws/session-token]}]
  {:access-key-id     access-key-id
   :secret-access-key secret-access-key
   :session-token     session-token
   :service-name      (or (service/signing-name service)
                          (service/endpoint-prefix service))
   :region            (or (get-in endpoint [:credentialScope :region])
                          (get-in endpoint [:region]))})

(defn signing-params
  [op expires {:strs [x-amz-date] :as headers-to-sign} auth-info amz-date]
  (assoc auth-info
         ;; TODO: we only need the op for presign, hence this
         ;; some->. Maybe there's a clearer way to express that
         ;; fact. Alternatively, we could always supply one, even
         ;; though we may not need it. That way this data is consistent
         ;; across different contexts/use cases.
         :op             (some-> op name)
         :algo           "AWS4-HMAC-SHA256"
         :credential     (format "%s/%s" (:access-key-id auth-info) (credential-scope auth-info amz-date))
         :amz-date       amz-date
         :expires        expires ;; seconds
         :signed-headers (signed-headers-string headers-to-sign)))

(defn- move-uri-qs-to-qs [req]
  (let [orig-qs (:query-string req)
        [uri uri-qs] (some-> req :uri (str/split #"\?"))
        new-qs (not-empty (str/join "&" (remove str/blank? [orig-qs uri-qs])))]
    (cond-> (assoc req :uri uri)
      new-qs
      (assoc :query-string new-qs))))

(defmethod signing/presigned-url :default
  [{:keys [http-request op service endpoint credentials]
    {:keys [expires]} :presigned-url}]
  (let [req*                    (-> http-request
                                    (update :headers normalize-headers)
                                    move-uri-qs-to-qs)
        amz-date                (get-in req* [:headers "x-amz-date"])
        req                     (update req* :headers dissoc "x-amz-date")
        auth-info               (auth-info service endpoint credentials)
        signing-params          (signing-params op expires (:headers req) auth-info amz-date)
        qs-params-no-sig        (into (sorted-map)
                                      (-> signing-params
                                          (select-keys [:op :algo :credential :amz-date :expires :signed-headers])
                                          (clojure.set/rename-keys {:op             "Action"
                                                                    :algo           "X-Amz-Algorithm"
                                                                    :credential     "X-Amz-Credential"
                                                                    :amz-date       "X-Amz-Date"
                                                                    :expires        "X-Amz-Expires"
                                                                    :signed-headers "X-Amz-SignedHeaders"})))
        qs-no-sig               (format-qs qs-params-no-sig)
        {:keys [signature]
         :as   signing-context} (->> {:signing-params signing-params
                                      :req            (assoc req
                                                             :query-string qs-no-sig
                                                             :body "UNSIGNED-PAYLOAD")}
                                     add-canonical-request
                                     add-signing-key
                                     add-string-to-sign
                                     add-signature)
        qs-params-with-sig      (assoc qs-params-no-sig "X-Amz-Signature" signature)
        qs-with-sig             (format-qs qs-params-with-sig)]
    {:presigned-url (str "https://" (:server-name req) (:uri req) "?"
                         (canonical-query-string {:query-string qs-with-sig}))
     :cognitect.aws.signing/basis (-> signing-context
                                      (dissoc :req)
                                      (update :signing-params dissoc :secret-access-key))}))

(defn v4-sign-http-request
  [service endpoint credentials http-request & {:keys [content-sha256-header?]}]
  (let [req                     (-> http-request
                                    (update :headers normalize-headers)
                                    move-uri-qs-to-qs)
        amz-date                (get-in http-request [:headers "x-amz-date"])
        hashed-body             (hashed-body (:body req))
        auth-info               (auth-info service endpoint credentials)
        signing-params          (signing-params "noop" 0 (:headers req) auth-info amz-date)
        {:keys [signature]
         :as   signing-context} (->> {:signing-params signing-params
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

(defmethod signing/sign-http-request "v4"
  [service endpoint credentials http-request]
  (v4-sign-http-request service endpoint credentials http-request))

(defmethod signing/sign-http-request "s3"
  [service endpoint credentials http-request]
  (v4-sign-http-request service endpoint credentials http-request :content-sha256-header? true))

(defmethod signing/sign-http-request "s3v4"
  [service endpoint credentials http-request]
  (v4-sign-http-request service endpoint credentials http-request :content-sha256-header? true))
