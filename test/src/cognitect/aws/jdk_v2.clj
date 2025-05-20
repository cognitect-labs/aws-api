(ns cognitect.aws.jdk-v2
  "Test utilities based on AWS Java SDK v2."
  (:require [clojure.string :as str]
            [cognitect.aws.util :as util])
  (:import [java.time Clock ZonedDateTime]
           [software.amazon.awssdk.auth.credentials AwsBasicCredentials]
           [software.amazon.awssdk.auth.signer Aws4Signer] ;; deprecated
           [software.amazon.awssdk.auth.signer.params Aws4SignerParams] ;; deprecated
           [software.amazon.awssdk.regions Region]
           [software.amazon.awssdk.http.auth.aws.signer AwsV4HttpSigner]
           [software.amazon.awssdk.http ContentStreamProvider SdkHttpMethod SdkHttpRequest]))

(defn ^:private ->http-method
  [request]
  (SdkHttpMethod/fromValue (name (:request-method request))))

(defn ^:private ->x-amz-date-clock
  "Returns a fixed `java.time.Clock` which the signer will use to create `x-amz-date` header."
  [request]
  (let [zdt (-> (get-in request [:headers "x-amz-date"])
                (ZonedDateTime/parse util/x-amz-date-format))]
    (Clock/fixed (.toInstant zdt) (.getZone zdt))))

(defn ^:private ->basic-credentials
  [credentials]
  (AwsBasicCredentials/create (:aws/access-key-id credentials)
                              (:aws/secret-access-key credentials)))

(defn ^:private ->default-request
  "Returns an instance of `software.amazon.awssdk.http.SdkHttpFullRequest`"
  [request]
  (let [[path query] (str/split (:uri request) #"\?" 2)
        req (doto (SdkHttpRequest/builder)
              (.uri (str "https://" (get-in request [:headers "host"])))
              (.method (->http-method request))
              (.contentStreamProvider (ContentStreamProvider/fromByteArray (:body request)))
              (.encodedPath (str/replace path #"^//+" "/")))]
    (when (seq query)
      (doseq [[k v] (map #(str/split % #"=") (str/split query #"&"))]
        (.appendRawQueryParameter req k v)))
    (.build req)))

(defn- sdk-request->request-map
  "Accepts an instance of `software.amazon.awssdk.http.SdkHttpRequest`, returns a map
  representation of the signed request. Currently the only key is `:headers` whose value is a map.
  That map has keys which are lowercased keywords and String values."
  [sdk-request]
  {:headers
   (into
    {}
    (map (fn [e] [(-> e .getKey str/lower-case keyword) (-> e .getValue first)]))
    (-> sdk-request .headers))})

(defn- aws-v4-signed-request
  "Performs 'v4', 's3v4' or 's3' request signing. Returns a map representation of the signed request.

  See `AwsV4HttpSigner` javadoc for helpful code snippet.

  See https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_aws-signing.html

  Note: the `sign` method returns an instance of
  `software.amazon.awssdk.http.auth.spi.signer.SignedRequest`. Invoke `.request` on it to get an
  instance of `software.amazon.awssdk.http.SdkHttpRequest`"
  [service region credentials request]
  (let [basic-credentials  (->basic-credentials credentials)
        req                (->default-request request)
        clock              (->x-amz-date-clock request)
        signer             (AwsV4HttpSigner/create)
        signing-name       (get-in service [:metadata :signingName])
        signature-version  (get-in service [:metadata :signatureVersion])
        double-url-encode? (= "v4" signature-version)
        normalize-path?    (= "v4" signature-version)
        payload-sign?      (some? (#{"s3v4" "s3"} signature-version))]
    (-> signer
        (.sign (fn [r] (doto r
                         (.identity basic-credentials)
                         (.request req)
                         (.payload (-> req .contentStreamProvider .get))
                         (.putProperty AwsV4HttpSigner/SERVICE_SIGNING_NAME signing-name)
                         (.putProperty AwsV4HttpSigner/REGION_NAME region)
                         (.putProperty AwsV4HttpSigner/SIGNING_CLOCK clock)
                         (.putProperty AwsV4HttpSigner/DOUBLE_URL_ENCODE double-url-encode?)
                         (.putProperty AwsV4HttpSigner/NORMALIZE_PATH normalize-path?)
                         (.putProperty AwsV4HttpSigner/PAYLOAD_SIGNING_ENABLED payload-sign?)
                         (.putProperty AwsV4HttpSigner/CHUNK_ENCODING_ENABLED false))))
        .request
        sdk-request->request-map)))

(def s3v4-jdk-signed-request aws-v4-signed-request)

;; TODO uncomment after deleting `v4-jdk-signed-request` function with deprecated implementation
;; (def v4-jdk-signed-request aws-v4-signed-request)

(defn v4-jdk-signed-request
  "To match the current aws-api signing behavior for 'v4' signing, the `x-amz-content-sha256` header
  needs to be entirely omitted from signing. That does not appear to be possible to do with the
  `AwsV4HttpSigner` class - even when payload signing is disabled, it creates a
  `x-amz-content-sha256` header with a value of 'UNSIGNED-PAYLOAD' and includes this header in the
  signed headers, which results in a different signature than if the header were omitted entirely.

  Therefore, this function uses the deprecated `Aws4Signer` class, for which it is possible to omit
  that header.

  TODO If aws-api is ever changed to do the 'UNSIGNED-PAYLOAD' thing for 'v4' signing, this function
  can be deleted and the `aws-v4-signed-request` function can be used. Note that expected test
  results in this repository will change.

  Note, the `sign` method returns an instance of `software.amazon.awssdk.http.SdkHttpRequest`."
  [service region credentials request]
  (let [signing-name (get-in service [:metadata :signingName])
        clock (->x-amz-date-clock request)
        req (->default-request request)]
    (-> (Aws4Signer/create)
        (.sign req (-> (Aws4SignerParams/builder)
                       (.doubleUrlEncode true)
                       (.normalizePath true)
                       (.awsCredentials (->basic-credentials credentials))
                       (.signingName signing-name)
                       (.signingRegion (Region/of region))
                       (.signingClockOverride clock)
                       (.build)))
        sdk-request->request-map)))
