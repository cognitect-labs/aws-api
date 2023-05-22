;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.protocols-test
  "Test the protocols implementations."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing use-fixtures]]
            [cognitect.aws.client.impl :as client]
            [cognitect.aws.protocols :as aws.protocols]
            [cognitect.aws.protocols.ec2]
            [cognitect.aws.protocols.json]
            [cognitect.aws.protocols.query]
            [cognitect.aws.protocols.rest-json]
            [cognitect.aws.protocols.rest-xml]
            [cognitect.aws.util :as util])
  (:import (java.nio ByteBuffer)
           (java.util Date)))

(s/fdef cognitect.aws.util/query-string
  :args (s/cat :params (s/or :seq (s/coll-of (s/cat :key (s/or :kw simple-keyword? :str string?)
                                                    :val string?))
                             :map (s/map-of (s/or :kw simple-keyword? :str string?) string?))))

(defn instrument-fixture [f]
  (try
    (stest/instrument ['cognitect.aws.util/query-string])
    (f)
    (finally
      (stest/unstrument))))

(use-fixtures :once instrument-fixture)

(defn test-request-method
  [expected {:keys [request-method]}]
  (when expected
    (is (= (keyword (str/lower-case expected)) request-method))))

(defn test-request-uri
  [expected {:keys [uri]}]
  (is (= expected uri)))

(defn test-request-headers
  [expected {:keys [headers]}]
  (doseq [[k v] expected
          :let [s (str/lower-case (name k))]]
    (is (contains? headers s))
    (is (= v (get headers s)))))

(defn get-bytes [s] (.getBytes s))

(defn update-many [data paths fn]
  (if (empty? paths)
    data
    (let [path (first paths)
          path (if (sequential? path)
                 path
                 [path])]
      (update-many (if (get-in data path)
                     (update-in data path fn)
                     data)
                   (rest paths)
                   fn))))

(defn update-params [data paths fn]
  (update data :params update-many paths fn))

(defmulti with-blob-xforms
  "The botocore tests we're taking advantage of here all assume that
  we accept strings for blob types, but this library does not.  Use
  this multimethod to xform strings to byte arrays before submitting
  requests to invoke."
  (fn [protocol test-name _test-case]
    [protocol test-name]))

(defmethod with-blob-xforms :default
  [_ _ test-case] test-case)

(defmethod with-blob-xforms ["ec2" "Base64 encoded Blobs"]
  [_ _ test-case]
  (update-params test-case [:BlobArg] get-bytes))

(defmethod with-blob-xforms ["query" "Base64 encoded Blobs"]
  [_ _ test-case]
  (update-params test-case [:BlobArg] get-bytes))

(defmethod with-blob-xforms ["json" "Base64 encoded Blobs"]
  [_ _ test-case]
  (update-params test-case
                 [:BlobArg
                  [:BlobMap :key1]
                  [:BlobMap :key2]]
                 get-bytes))

(defmethod with-blob-xforms ["json" "Nested blobs"]
  [_ _ test-case]
  (update-params test-case
                 [[:ListParam 0]
                  [:ListParam 1]]
                 get-bytes))

(defmethod with-blob-xforms ["rest-xml" "Blob and timestamp shapes"]
  [_ _ test-case]
  (update-params test-case [[:StructureParam :b]] get-bytes))

(defmethod with-blob-xforms ["rest-xml" "Blob payload"]
  [_ _ test-case]
  (update-params test-case [:foo] get-bytes))

(defmethod with-blob-xforms ["rest-json" "Blob and timestamp shapes"]
  [_ _ test-case]
  (update-in test-case [:params :Bar] get-bytes))

(defmethod with-blob-xforms ["rest-json" "Serialize blobs in body"]
  [_ _ test-case]
  (update-in test-case [:params :Bar] get-bytes))

(defmethod with-blob-xforms ["query" "URL Encoded values in body"]
  [_ _ test-case]
  (update-in test-case [:params :Blob] get-bytes))

(defn timestamp->date [secs]
  (Date. (* secs 1000)))

(defmulti with-timestamp-xforms (fn [protocol description _response]
                                  [protocol description]))

(defmethod with-timestamp-xforms :default
  [_ _ response] response)

(defmethod with-timestamp-xforms ["ec2" "Timestamp values"]
  [_ _ test-case]
  (update-params test-case [:TimeArg :TimeCustom :TimeFormat] timestamp->date))

(defmethod with-timestamp-xforms ["query" "Timestamp values"]
  [_ _ test-case]
  (update-params test-case [:TimeArg :TimeCustom :TimeFormat] timestamp->date))

(defmethod with-timestamp-xforms ["rest-xml" "Blob and timestamp shapes"]
  [_ _ test-case]
  (update-params test-case [[:StructureParam :t]] timestamp->date))

(defmethod with-timestamp-xforms ["rest-xml" "Timestamp in header"]
  [_ _ test-case]
  (update-params test-case [:TimeArgInHeader] timestamp->date))

(defmethod with-timestamp-xforms ["rest-xml" "Timestamp shapes"]
  [_ _ test-case]
  (update-params test-case
                 [:TimeArg
                  :TimeArgInQuery
                  :TimeArgInHeader
                  :TimeCustom
                  :TimeCustomInQuery
                  :TimeCustomInHeader
                  :TimeFormat
                  :TimeFormatInQuery
                  :TimeFormatInHeader]
                 timestamp->date))

(defmethod with-timestamp-xforms ["rest-json" "Timestamp values"]
  [_ _ test-case]
  (update-params test-case
                 [:TimeArg
                  :TimeArgInQuery
                  :TimeArgInHeader
                  :TimeCustom
                  :TimeCustomInQuery
                  :TimeCustomInHeader
                  :TimeFormat
                  :TimeFormatInQuery
                  :TimeFormatInHeader]
                 timestamp->date))

(defmethod with-timestamp-xforms ["query" "URL Encoded values in body"]
  [_ _ test-case]
  (update-params test-case [:Timestamp] timestamp->date))

(defmethod with-timestamp-xforms ["json" "Timestamp values"]
  [_ _ test-case]
  (update-params test-case [:TimeArg :TimeCustom :TimeFormat] timestamp->date))

(defmethod with-timestamp-xforms ["rest-json" "Named locations in JSON body"]
  [_ _ test-case]
  (update-params test-case [:TimeArg] timestamp->date))

(defmulti with-parsed-streams (fn [protocol description _response]
                                [protocol description]))

(def read-blob (comp slurp io/reader))

(defmethod with-parsed-streams :default
  [_ _ response] response)

(defmethod with-parsed-streams ["rest-xml" "Streaming payload"]
  [_ _ response] (update response :Stream read-blob))

(defmethod with-parsed-streams ["rest-json" "Streaming payload"]
  [_ _ response] (update response :Stream read-blob))

(defmethod with-parsed-streams ["rest-xml" "Blob"]
  [_ _ response]
  (update response :Blob read-blob))

(defmethod with-parsed-streams ["query" "Blob"]
  [_ _ response]
  (update response :Blob read-blob))

(defmethod with-parsed-streams ["ec2" "Blob"]
  [_ _ response]
  (update response :Blob read-blob))

(defmethod with-parsed-streams ["rest-json" "Blob members"]
  [_ _ response]
  (update-many response
               [:BlobMember
                [:StructMember :foo]]
               read-blob))

(defmethod with-parsed-streams ["json" "Blob members"]
  [_ _ response]
  (update-many response
               [:BlobMember
                [:StructMember :foo]]
               read-blob))

(defn date->ms [d] (when d (int (/ (.getTime d) 1000))))

(defmulti with-parsed-dates (fn [protocol description _response]
                              [protocol description]))

(defmethod with-parsed-dates :default
  [_ _ response] response)

(defmethod with-parsed-dates ["query" "Scalar members"]
  [_ _ response]
  (update response :Timestamp date->ms))

(defmethod with-parsed-dates ["rest-xml" "Scalar members"]
  [_ _ response]
  (update response :Timestamp date->ms))

(defmethod with-parsed-dates ["rest-xml" "Scalar members in headers"]
  [_ _ response]
  (update response :Timestamp date->ms))

(defmethod with-parsed-dates ["ec2" "Timestamp members"]
  [_ _ response]
  (update-many response [:TimeArg
                         :TimeCustom
                         :TimeFormat
                         [:StructMember :foo]
                         [:StructMember :bar]]
               date->ms))

(defmethod with-parsed-dates ["query" "Timestamp members"]
  [_ _ response]
  (update-many response [:TimeArg
                         :TimeCustom
                         :TimeFormat
                         [:StructMember :foo]
                         [:StructMember :bar]]
               date->ms))

(defmethod with-parsed-dates ["json" "Timestamp members"]
  [_ _ response]
  (update-many response [:TimeArg
                         :TimeCustom
                         :TimeFormat
                         [:StructMember :foo]
                         [:StructMember :bar]]
               date->ms))

(defmethod with-parsed-dates ["json" "Timestamp members with doubles"]
  [_ _ response]
  (update-many response [:TimeArg] date->ms))

(defmethod with-parsed-dates ["rest-xml" "Timestamp members"]
  [_ _ response]
  (update-many response [:TimeArg
                         :TimeArgInHeader
                         :TimeCustom
                         :TimeCustomInHeader
                         :TimeFormat
                         :TimeFormatInHeader
                         [:StructMember :foo]
                         [:StructMember :bar]]
               date->ms))

(defmethod with-parsed-dates ["rest-json" "Timestamp members"]
  [_ _ response]
  (update-many response [:TimeArg
                         :TimeArgInHeader
                         :TimeCustom
                         :TimeCustomInHeader
                         :TimeFormat
                         :TimeFormatInHeader
                         [:StructMember :foo]
                         [:StructMember :bar]]
               date->ms))

(defmethod with-parsed-dates ["rest-json" "Complex Map Values"]
  [_ _ response]
  (update-many response
               [[:MapMember :a]
                [:MapMember :b]]
               date->ms))

(defn stringify-keys [m] (reduce-kv (fn [m k v] (assoc m (name k) v)) {} m))
(defmulti with-string-keyed-maps (fn [protocol description _response] [protocol description]))
(defmethod with-string-keyed-maps :default [_ _ response] response)
(defmethod with-string-keyed-maps ["ec2" "Normal map"] [_ _ response] (update response :Map stringify-keys))
(defmethod with-string-keyed-maps ["ec2" "Flattened map"] [_ _ response] (update response :Map stringify-keys))
(defmethod with-string-keyed-maps ["ec2" "Named map"] [_ _ response] (update response :Map stringify-keys))
(defmethod with-string-keyed-maps ["query" "Normal map"] [_ _ response] (update response :Map stringify-keys))
(defmethod with-string-keyed-maps ["query" "Flattened map"] [_ _ response] (update response :Map stringify-keys))
(defmethod with-string-keyed-maps ["query" "Flattened map in shape definition"] [_ _ response] (update response :Map stringify-keys))
(defmethod with-string-keyed-maps ["query" "Named map"] [_ _ response] (update response :Map stringify-keys))
(defmethod with-string-keyed-maps ["rest-xml" "Normal map"] [_ _ response] (update response :Map stringify-keys))
(defmethod with-string-keyed-maps ["rest-xml" "Flattened map"] [_ _ response] (update response :Map stringify-keys))
(defmethod with-string-keyed-maps ["rest-xml" "Named map"] [_ _ response] (update response :Map stringify-keys))

(defmulti test-request-body (fn [protocol _expected _request] protocol))

(defmethod test-request-body :default
  [_ expected {:keys [body]}]
  (if (str/blank? expected)
    (is (nil? body))
    (is (= expected body))))

(defmethod test-request-body "json"
  [_ expected http-request]
  (is (= (some-> expected json/read-str)
         (some-> http-request :body json/read-str))))

(defmethod test-request-body "rest-xml"
  [_ expected {:keys [body]}]
  (is (= (some-> expected not-empty)
         ;; TODO (dchelimsky 2019-02-15) there is only one case
         ;; in which body is a byte array. This may change if/when
         ;; we expose build-http-request as an API and settle on
         ;; a type for body.
         (if (bytes? body) (slurp body) body))))

(defmethod test-request-body "rest-json"
  [_ expected http-request]
  (let [body-str (some-> http-request :body)]
    (if (str/blank? expected)
      (is (nil? body-str))
      (if-let [expected-json (try (json/read-str expected)
                                  (catch Throwable _t))]
        (is (= expected-json (json/read-str body-str)))
        ;; streaming, no JSON payload, we compare strings directly
        (is (= expected body-str))))))

(defn parse-query-string [s]
  (->> (str/split s #"&")
       (map #(str/split % #"="))
       (map (fn [[a b]] [a b]))
       (into {})))

(defmethod test-request-body "query"
  [_ expected {:keys [body]}]
  (if (str/blank? expected)
    (is (nil? body))
    (is (= (parse-query-string expected)
           (parse-query-string body)))))

(defmulti run-test (fn [io _protocol _description _service _test-case] io))

(defmethod run-test "input"
  [_ protocol description service test-case]
  (let [{expected :serialized :keys [given params]}
        (->> test-case
             (with-blob-xforms protocol description)
             (with-timestamp-xforms protocol description))]
    (try
      (let [op-map       {:op (:name given) :request params}
            http-request (aws.protocols/build-http-request service op-map)]
        (test-request-method (:method expected) http-request)
        (test-request-uri (:uri expected) http-request)
        (test-request-headers (:headers expected) http-request)
        (test-request-body (get-in service [:metadata :protocol]) (:body expected) http-request))
      (catch Exception e
        (is (nil?
             {:expected  expected
              :test-case test-case
              :exception e}))))))

(defmethod run-test "output"
  [_ protocol description service {:keys [given response result] :as test-case}]
  (try
    (let [op-map          {:op (:name given)}
          parsed-response (aws.protocols/parse-http-response service
                                                             op-map
                                                             {:status  (:status_code response)
                                                              :headers (:headers response)
                                                              :body    (util/->bbuf (:body response))})]
      (when (:cognitect.anomalies/category parsed-response)
        (throw (or (::client/throwable parsed-response)
                   (ex-info "Client Error." parsed-response))))
      (is (= (->> result
                  (with-string-keyed-maps protocol description))
             (->> parsed-response
                  (with-parsed-streams protocol description)
                  (with-parsed-dates protocol description)))))
    (catch Exception e
      (is (nil? {:test-case test-case :exception e})))))

(defn test-protocol
  ([protocol]
   (test-protocol protocol "input")
   (test-protocol protocol "output"))
  ([protocol input-or-output]
   (let [filepath       (str "botocore/protocols/" input-or-output "/" protocol ".json")
         extra-filepath (str "cognitect/protocols/" input-or-output "/" protocol ".json")]
     (doseq [test (into (-> filepath io/resource slurp (json/read-str :key-fn keyword))
                        (when (io/resource extra-filepath)
                          (-> extra-filepath io/resource slurp (json/read-str :key-fn keyword))))]
       (testing (str input-or-output " of " protocol " : " (:description test))
         (doseq [{:keys [given] :as test-case} (:cases test)
                 :let                          [service (assoc (select-keys test [:metadata :shapes])
                                                               :operations {(:name given) given})]]
           (run-test input-or-output protocol (:description test) service test-case)))))))

(deftest test-protocols
  (with-redefs [util/uuid-string (constantly "00000000-0000-4000-8000-000000000000")]
    (doseq [protocol ["ec2"
                      "query"
                      "json"
                      "rest-xml"
                      "rest-json"]]
      (test-protocol protocol))))

(deftest test-parse-http-error-response
  (testing "parse JSON-encoded error response body"
    (let [response {:status 401
                    :body   (ByteBuffer/wrap (.getBytes "{\"FOO\": \"abc\"}" "UTF-8"))}
          parsed-response (aws.protocols/parse-http-error-response response)]
      (is (= {:FOO "abc" :cognitect.anomalies/category :cognitect.anomalies/incorrect}
             parsed-response))
      (testing "http response is included as metadata on returned parsed error response"
        (is (= response (meta parsed-response))))))
  (testing "parse XML-encoded response body - issue 218: AWS returns XML-encoded 404 response when JSON-encoding was expected"
    (let [response {:status 404
                    :body   (util/->bbuf "<UnknownOperationException/>")}
          parsed-response (aws.protocols/parse-http-error-response response)]
      (is (= {:UnknownOperationException nil
              :UnknownOperationExceptionAttrs {}
              :cognitect.anomalies/category :cognitect.anomalies/not-found}
             parsed-response))
      (testing "http response is included as metadata on returned parsed error response"
        (is (= response (meta parsed-response))))))
  (testing "parse response with empty body"
    (let [response {:status 404
                    :body   nil}
          parsed-response (aws.protocols/parse-http-error-response response)]
      (is (= {:cognitect.anomalies/category :cognitect.anomalies/not-found}
             parsed-response))
      (testing "http response is included as metadata on returned parsed error response"
        (is (= response (meta parsed-response)))))))

(deftest anomaly-tranformations
  (testing "301 gets :cognitect.anomalies/incorrect"
    (is (= :cognitect.anomalies/incorrect
           (:cognitect.anomalies/category
            (aws.protocols/parse-http-error-response
             {:status 301})))))
  (testing "301 with x-amz-bucket-region header gets custom anomalies/message"
    (is (= "The bucket is in this region: us-east-1. Please use this region to retry the request."
           (:cognitect.anomalies/message
            (aws.protocols/parse-http-error-response
             {:status 301
              :headers {"x-amz-bucket-region" "us-east-1"}})))))
  (testing "ThrottlingException gets :cognitect.anomalies/busy"
    (is (= :cognitect.anomalies/busy
           (:cognitect.anomalies/category
            (aws.protocols/parse-http-error-response
             {:status 400
              :body (util/->bbuf (json/json-str {:__type "ThrottlingException"}))}))))
    (is (= :cognitect.anomalies/busy
           (:cognitect.anomalies/category
            (aws.protocols/parse-http-error-response
             {:status 400
              :body (util/->bbuf "<Error><Code>ThrottlingException</Code></Error>")}))))))

(comment
  (t/run-tests))
