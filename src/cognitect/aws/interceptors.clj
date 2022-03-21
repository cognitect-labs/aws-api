;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.interceptors
  "Impl, don't call directly."
  (:require [cognitect.aws.service :as service]
            [cognitect.aws.util :as util]))

(set! *warn-on-reflection* true)

(defmulti modify-http-request (fn [service op-map http-request]
                                (service/service-name service)))

(defmethod modify-http-request :default [service op-map http-request] http-request)

(def checksum-blacklist
  "Set of ops that should not get checksum headers.

  see https://github.com/aws/aws-sdk-java-v2/blob/30660f43ec485b20b39d7ea6743bdf43b2b7faa1/services/s3/src/main/java/software/amazon/awssdk/services/s3/internal/handlers/AddContentMd5HeaderInterceptor.java"
  #{:PutObject :UploadPart})

(defmethod modify-http-request "s3" [service op-map http-request]
  (if (and (not (checksum-blacklist (:op op-map)))
           (:body http-request))
    (case (get-in service [:metadata :checksumFormat])
      "md5"    (update http-request :headers assoc "Content-MD5"           (-> http-request :body util/md5 util/base64-encode))
      "sha1"   (update http-request :headers assoc "x-amz-checksum-sha1"   (-> http-request :body util/sha1 util/base64-encode))
      "sha256" (update http-request :headers assoc "x-amz-checksum-sha256" (-> http-request :body util/sha256 util/base64-encode))
      "crc32"  (update http-request :headers assoc "x-amz-checksum-crc32"  (-> http-request :body util/crc32 util/base64-encode))
      "crc32c" (update http-request :headers assoc "x-amz-checksum-crc32c" (-> http-request :body util/crc32c util/base64-encode))
      http-request)
    http-request))

(defmethod modify-http-request "apigatewaymanagementapi" [service op-map http-request]
  (if (= :PostToConnection (:op op-map))
    (update http-request :uri #(str % (-> op-map :request :ConnectionId)))
    http-request))

;; See https://github.com/aws/aws-sdk-java-v2/blob/985ec92c0dfac868b33791fe4623296c68e2feab/services/glacier/src/main/java/software/amazon/awssdk/services/glacier/internal/GlacierExecutionInterceptor.java#L40
(defmethod modify-http-request "glacier" [service op-map http-request]
  (assoc-in http-request [:headers "x-amz-glacier-version"] (get-in service [:metadata :apiVersion])))
