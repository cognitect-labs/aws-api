;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.protocols-test
  "Test the protocols implementations."
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.walk :as walk]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [cognitect.aws.util :as util]
            [cognitect.aws.client :as client]
            [cognitect.aws.protocols.json]
            [cognitect.aws.protocols.rest-json]
            [cognitect.aws.protocols.rest-xml]
            [cognitect.aws.protocols.query]
            [cognitect.aws.protocols.ec2]
            [cognitect.aws.test.utils :as test.utils])
  (:import (java.util Date)))

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
  [expected {:keys [headers] :as foo}]
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

(defn timestamp->date [secs]
  (Date. (* secs 1000)))

(defn xform-timestamp-params [test-case ks]
  (update-params test-case ks timestamp->date))

(defmulti with-timestamp-xforms (fn [protocol description response]
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

(defmulti with-parsed-streams (fn [protocol description response]
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

(defmulti with-parsed-dates (fn [protocol description response]
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
                         [:StructMember :bar] ]
               date->ms))

(defmethod with-parsed-dates ["query" "Timestamp members"]
  [_ _ response]
  (update-many response [:TimeArg
                         :TimeCustom
                         :TimeFormat
                         [:StructMember :foo]
                         [:StructMember :bar] ]
               date->ms))

(defmethod with-parsed-dates ["json" "Timestamp members"]
  [_ _ response]
  (update-many response [:TimeArg
                         :TimeCustom
                         :TimeFormat
                         [:StructMember :foo]
                         [:StructMember :bar] ]
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
                         [:StructMember :bar] ]
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
                         [:StructMember :bar] ]
               date->ms))

(defmethod with-parsed-dates ["rest-json" "Complex Map Values"]
  [_ _ response]
  (update-many response
               [[:MapMember :a]
                [:MapMember :b]]
               date->ms))

(defn stringify-keys [m] (reduce-kv (fn [m k v] (assoc m (name k) v)) {} m))
(defmulti with-string-keyed-maps (fn [protocol description response] [protocol description]))
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

(defmulti test-request-body (fn [protocol expected request] protocol))

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
         body)))

(defmethod test-request-body "rest-json"
  [_ expected http-request]
  (let [body-str (some-> http-request :body)]
    (if (str/blank? expected)
      (is (nil? body-str))
      (if-let [expected-json (try (json/read-str expected)
                                  (catch Throwable t))]
        (is (= expected-json (json/read-str body-str)))
        ;; streaming, no JSON payload, we compare strings directly
        (is (= expected body-str))))))

(defmethod test-request-body "query"
  [_ expected {:keys [body]}]
  (if (str/blank? expected)
    (is (nil? body))
    (is (= (test.utils/query-string->map expected)
           (test.utils/query-string->map body)))))

(defmulti run-test (fn [io protocol description service test-case] io))

(defmethod run-test "input"
  [_ protocol description service test-case]
  (let [{expected :serialized :keys [given params]}
        (with-timestamp-xforms protocol description test-case)]
    (try
      (let [op-map       {:op (:name given) :request params}
            http-request (client/build-http-request service op-map)]
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
          body-bytes (.getBytes (:body response))
          received-response {:status  (:status_code response)
                             :headers (reduce-kv (fn [m k v]
                                                   (assoc m (name k) v))
                                                 {}
                                                 (:headers response))
                             :response-body-as :inputstream
                             :body (java.io.ByteArrayInputStream. body-bytes)}
          parsed-response (client/parse-http-response service op-map received-response)]
      (when-let [anomaly (:cognitect.anomalies/category parsed-response)]
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

(comment
  (run-tests)

  )
