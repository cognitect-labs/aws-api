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

(def md5-blacklist
  "Set of ops that should not get the Content-MD5 header.

  See https://github.com/aws/aws-sdk-java-v2/blob/master/services/s3/src/main/java/software/amazon/awssdk/services/s3/internal/handlers/AddContentMd5HeaderInterceptor.java "
  #{:PutObject :UploadPart})

(defmethod modify-http-request "s3" [service op-map http-request]
  (if (and (= "md5" (get-in service [:metadata :checksumFormat]))
           (not (md5-blacklist (:op op-map)))
           (:body http-request))
    (update http-request :headers assoc "Content-MD5" (-> http-request :body util/md5 util/base64-encode))
    http-request))

(defmethod modify-http-request "apigatewaymanagementapi" [service op-map http-request]
  (if (= :PostToConnection (:op op-map))
    (update http-request :uri #(str % (-> op-map :request :ConnectionId)))
    http-request))

;; See https://github.com/aws/aws-sdk-java-v2/blob/985ec92c0dfac868b33791fe4623296c68e2feab/services/glacier/src/main/java/software/amazon/awssdk/services/glacier/internal/GlacierExecutionInterceptor.java#L40
(defmethod modify-http-request "glacier" [service op-map http-request]
  (assoc-in http-request [:headers "x-amz-glacier-version"] (get-in service [:metadata :apiVersion])))
