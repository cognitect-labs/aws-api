(ns cognitect.aws.jdk
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cognitect.aws.util :as util])
  (:import [java.net URI]
           [com.amazonaws DefaultRequest]
           [com.amazonaws.auth AWS4Signer]
           [com.amazonaws.auth BasicAWSCredentials]
           [com.amazonaws.http HttpMethodName]
           [com.amazonaws.services.s3.internal AWSS3V4Signer]
           [com.amazonaws.services.s3.request S3HandlerContextKeys]))

(defn ^:private ->http-method
  [request]
  (HttpMethodName/fromValue (name (:request-method request))))

(defn ^:private ->x-amz-date
  [request]
  (->> (get-in request [:headers "x-amz-date"])
       (util/parse-date util/x-amz-date-format)))

(defn ^:private ->basic-credentials
  [credentials]
  (BasicAWSCredentials. (:aws/access-key-id credentials)
                        (:aws/secret-access-key credentials)))

(defn ^:private ->default-request
  [service request]
  (let [[path query] (str/split (:uri request) #"\?" 2)
        req (doto (DefaultRequest. (get-in service [:metadata :signingName]))
              (.setHttpMethod (->http-method request))
              (.setContent (io/input-stream (:body request)))
              (.setEndpoint (URI. (str "https://" (get-in request [:headers "host"]))))
              (.setResourcePath (str/replace path #"^//+" "/")))]
    (when (seq query)
      (doseq [[k v] (map #(str/split % #"=") (str/split query #"&"))]
        (.addParameter req k v)))
    req))

(defn v4-jdk-signed-request
  [service credentials request]
  (let [basic-credentials (->basic-credentials credentials)
        req               (->default-request service request)
        signer            (doto (AWS4Signer.)
                            (.setServiceName (get-in service [:metadata :signingName]))
                            (.setOverrideDate (->x-amz-date request)))]
    (.sign signer req basic-credentials)
    req))

(defn s3v4-jdk-signed-request
  [service credentials request]
  (let [basic-credentials (->basic-credentials credentials)
        req               (doto (->default-request service request)
                            (.addHandlerContext S3HandlerContextKeys/IS_PAYLOAD_SIGNING_ENABLED true)
                            (.addHandlerContext S3HandlerContextKeys/IS_CHUNKED_ENCODING_DISABLED true))
        signer            (doto (AWSS3V4Signer.)
                            (.setServiceName (get-in service [:metadata :signingName]))
                            (.setOverrideDate (->x-amz-date request)))]
    (.sign signer req basic-credentials)
    req))
