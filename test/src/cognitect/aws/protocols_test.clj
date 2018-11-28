;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.protocols-test
  "Test the protocols implementations."
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [cognitect.aws.util :as util]
            [cognitect.aws.client :as client]
            [cognitect.aws.protocols.json]
            [cognitect.aws.protocols.rest-json]
            [cognitect.aws.protocols.rest-xml]
            [cognitect.aws.protocols.query]
            [cognitect.aws.protocols.ec2])
  (:import (java.util Date)))

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

(defmulti with-blob-xforms
  "The botocore tests we're taking advantage of here all assume that
  we accept strings for blob types, but this library does not.  Use
  this multimethod to xform strings to byte arrays before submitting
  requests to invoke."
  (fn [protocol test-name test-case]
    [protocol test-name]))

(defmethod with-blob-xforms :default
  [_ _ test-case] test-case)

(defmethod with-blob-xforms ["ec2" "Base64 encoded Blobs"]
  [_ _ test-case]
  (update-in test-case [:params :BlobArg] get-bytes))

(defmethod with-blob-xforms ["query" "Base64 encoded Blobs"]
  [_ _ test-case]
  (update-in test-case [:params :BlobArg] get-bytes))

(defmethod with-blob-xforms ["json" "Base64 encoded Blobs"]
  [_ _ test-case]
  (if (get-in test-case [:params :BlobArg])
    (update-in test-case [:params :BlobArg] get-bytes)
    (-> test-case
        (update-in [:params :BlobMap :key1] get-bytes)
        (update-in [:params :BlobMap :key2] get-bytes))))

(defmethod with-blob-xforms ["json" "Nested blobs"]
  [_ _ test-case]
  (-> test-case
      (update-in [:params :ListParam 0] get-bytes)
      (update-in [:params :ListParam 1] get-bytes)))

(defmethod with-blob-xforms ["rest-xml" "Blob and timestamp shapes"]
  [_ _ test-case]
  (update-in test-case [:params :StructureParam :b] get-bytes))

(defmethod with-blob-xforms ["rest-xml" "Blob payload"]
  [_ _ test-case]
  (if (get-in test-case [:params :foo])
    (update-in test-case [:params :foo] get-bytes)
    test-case))

(defmethod with-blob-xforms ["rest-json" "Blob and timestamp shapes"]
  [_ _ test-case]
  (update-in test-case [:params :Bar] get-bytes))

(defmethod with-blob-xforms ["rest-json" "Serialize blobs in body"]
  [_ _ test-case]
  (update-in test-case [:params :Bar] get-bytes))

(defmulti with-timestamp-xforms (fn [protocol description response]
                                [protocol description]))

(defmethod with-timestamp-xforms :default
  [_ _ response] response)

(defmethod with-timestamp-xforms ["ec2" "Timestamp values"]
  [_ _ test-case]
  (update-in test-case [:params :TimeArg] #(Date. (* % 1000))))

(defmethod with-timestamp-xforms ["query" "Timestamp values"]
  [_ _ test-case]
  (update-in test-case [:params :TimeArg] #(Date. (* % 1000))))

(defmethod with-timestamp-xforms ["rest-xml" "Blob and timestamp shapes"]
  [_ _ test-case]
  (update-in test-case [:params :StructureParam :t] #(Date. (* % 1000))))

(defmethod with-timestamp-xforms ["rest-xml" "Timestamp in header"]
  [_ _ test-case]
  (update-in test-case [:params :TimeArgInHeader] #(Date. (* % 1000))))

(defmethod with-timestamp-xforms ["rest-json" "Timestamp values"]
  [_ _ test-case]
  (cond (get-in test-case [:params :TimeArg])
        (update-in test-case [:params :TimeArg] #(Date. (* % 1000)))
        (get-in test-case [:params :TimeArgInHeader])
        (update-in test-case [:params :TimeArgInHeader] #(Date. (* % 1000)))
        :else
        test-case))

(defmethod with-timestamp-xforms ["json" "Timestamp values"]
  [_ _ test-case]
  (update-in test-case [:params :TimeArg] #(Date. (* % 1000))))

(defmethod with-timestamp-xforms ["rest-json" "Named locations in JSON body"]
  [_ _ test-case]
  (update-in test-case [:params :TimeArg] #(Date. (* % 1000))))

(defmulti with-parsed-streams (fn [protocol description response]
                                [protocol description]))

(defmethod with-parsed-streams :default
  [_ _ response] response)

(defmethod with-parsed-streams ["rest-xml" "Streaming payload"]
  [_ _ response] (update response :Stream (comp slurp io/reader)))

(defmethod with-parsed-streams ["rest-json" "Streaming payload"]
  [_ _ response] (update response :Stream (comp slurp io/reader)))

(defmethod with-parsed-streams ["rest-xml" "Blob"]
  [_ _ response]
  (update response :Blob (comp slurp io/reader)))

(defmethod with-parsed-streams ["query" "Blob"]
  [_ _ response]
  (update response :Blob (comp slurp io/reader)))

(defmethod with-parsed-streams ["ec2" "Blob"]
  [_ _ response]
  (update response :Blob (comp slurp io/reader)))

(defmethod with-parsed-streams ["rest-json" "Blob members"]
  [_ _ response]
  (-> response
      (update :BlobMember (comp slurp io/reader))
      (update-in [:StructMember :foo] (comp slurp io/reader))))

(defmethod with-parsed-streams ["json" "Blob members"]
  [_ _ response]
  (-> response
      (update :BlobMember (comp slurp io/reader))
      (update-in [:StructMember :foo] (comp slurp io/reader))))

(defmulti with-parsed-dates (fn [protocol description response]
                                [protocol description]))

(defmethod with-parsed-dates :default
  [_ _ response] response)

(defmethod with-parsed-dates ["query" "Scalar members"]
  [_ _ response]
  (-> response
      (update :Timestamp #(/ (.getTime %) 1000))))

(defmethod with-parsed-dates ["rest-xml" "Scalar members"]
  [_ _ response]
  (-> response
      (update :Timestamp #(/ (.getTime %) 1000))))

(defmethod with-parsed-dates ["rest-xml" "Scalar members in headers"]
  [_ _ response]
  (-> response
      (update :Timestamp #(/ (.getTime %) 1000))))

(defmulti test-request-body (fn [protocol expected request] protocol))

(defmethod test-request-body :default
  [_ expected {:keys [body]}]
  (let [body-str (util/bbuf->str body)]
    (if (str/blank? expected)
      (is (nil? body-str))
      (is (= expected body-str)))))

(defmethod test-request-body "json"
  [_ expected http-request]
  (is (= (some-> expected json/read-str)
         (some-> http-request :body util/bbuf->str json/read-str))))

(defmethod test-request-body "rest-xml"
  [_ expected http-request]
  (is (= (not-empty expected)
         (some-> http-request :body util/bbuf->str))))

(defmethod test-request-body "rest-json"
  [_ expected http-request]
  (let [body-str (some-> http-request :body util/bbuf->str)]
    (if (str/blank? expected)
      (is (nil? body-str))
      (if-let [expected-json (try (json/read-str expected)
                                  (catch Throwable t))]
        (is (= expected-json (some-> body-str json/read-str)))
        ;; streaming, no JSON payload, we compare strings directly
        (is (= expected body-str))))))

(defmulti run-test (fn [io protocol description service test-case] io))

(defmethod run-test "input"
  [_ protocol description service test-case]
  (let [{expected :serialized :keys [given params]}
        (->> test-case
             (with-blob-xforms protocol description)
             (with-timestamp-xforms protocol description))]
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
          parsed-response (client/parse-http-response service
                                                      op-map
                                                      {:status  (:status_code response)
                                                       :headers (:headers response)
                                                       :body    (util/->bbuf (:body response))})]
      (when-let [anomaly (:cognitect.anomalies/category parsed-response)]
        (throw (or (::client/throwable parsed-response)
                   (ex-info "Client Error." parsed-response))))
      (is (= result (->> parsed-response
                         (with-parsed-streams protocol description)
                         (with-parsed-dates protocol description)))))
    (catch Exception e
      (is (nil?
           {:test-case test-case
            :exception e})))))

(defn test-protocol
  ([protocol]
   (test-protocol protocol "input")
   (test-protocol protocol "output"))
  ([protocol input-or-output]
  (let [filepath (str "botocore/protocols/" input-or-output "/" protocol ".json")]
    (doseq [test (-> filepath io/resource slurp (json/read-str :key-fn keyword))]
      (testing (str input-or-output " of " protocol " : " (:description test))
        (doseq [{:keys [given] :as test-case} (:cases test)
                :let [service (assoc (select-keys test [:metadata :shapes])
                                     :operations {(:name given) given})]]
          (run-test input-or-output protocol (:description test) service test-case)))))))

(deftest test-protocols
  (with-redefs [util/gen-idempotency-token (constantly "00000000-0000-4000-8000-000000000000")]
    (doseq [protocol ["ec2"
                      "query"
                      "json"
                      "rest-xml"
                      "rest-json"]]
      (test-protocol protocol))))

(comment
  (run-tests)

  )
