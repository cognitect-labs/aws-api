(ns cognitect.aws.generators
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [cognitect.aws.util :as util]))

;; see cognitect.aws.protocols.rest/serialize-uri
;; we want to mimic inputs to cognitect.aws.signers/sign-http-request
(defn ^:private serialize-path-part
  [part]
  (-> part
      (util/url-encode)
      (str/replace "%2F" "/")
      (str/replace "%7E" "~")))

(defn gen-service
  [sig-ver]
  (gen/let [service-name (gen/such-that seq gen/string-alphanumeric)]
    {:metadata {:signatureVersion sig-ver
                :signingName service-name}}))

(def gen-request
  (gen/let [host (gen/fmap #(str % ".com") (gen/such-that seq gen/string-alphanumeric))
            path-parts (gen/vector (gen/fmap serialize-path-part (gen/such-that (complement str/blank?) gen/string-ascii)) 1 10)
            path-separator (gen/elements ["/" "//"])
            query-ks (gen/vector (gen/such-that seq gen/string-alphanumeric))
            query-vs (gen/vector (gen/such-that seq gen/string-alphanumeric) (count query-ks))
            method (gen/elements [:get :post])
            ;; https://github.com/aws/aws-sdk-java/blob/d35b018/aws-java-sdk-core/src/main/java/com/amazonaws/auth/internal/SignerKey.java#L30-L34
            ;; date must be >1 day past epoch and <= today
            ;; 1668999574880 == 2022-11-20
            epoch (gen/large-integer* {:min 86400000 :max 1668999574880})
            body gen/string]
    {:request-method method
     :body           (.getBytes ^String body "UTF-8")
     :headers        {"x-amz-date" (util/format-date util/x-amz-date-format (java.util.Date. epoch))
                      "host"       host}
     :uri            (str path-separator
                          (str/join path-separator path-parts)
                          (when (seq query-ks)
                            (str "?" (str/join "&" (map #(str %1 "=" %2)
                                                        query-ks
                                                        query-vs)))))}))
