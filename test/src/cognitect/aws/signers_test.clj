;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.signers-test
  "See http://docs.aws.amazon.com/general/latest/gr/signature-v4-test-suite.html"
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [cognitect.aws.jdk-v2 :as jdk-v2]
            [cognitect.aws.generators :as g]
            [cognitect.aws.signers :as signers])
  (:import [java.io ByteArrayInputStream]
           [org.apache.commons.io.input BOMInputStream]))

(def exclude-dir?
  "These dirs have subdirs with tests, but no tests directly in them."
  #{"post-sts-token"
    "normalize-path"})

(defn parse-request-line
  [request-line]
  (let [[_ request-method uri] (re-find #"^([A-Za-z]+)\s(.*)\s(HTTP.*)$" request-line)
        [path query-string] (str/split uri #"\?" 2)]
    {:request-method (keyword (str/lower-case request-method))
     :uri path
     :query-string query-string}))

(defn parse-headers
  [lines]
  (loop [[line & rest] lines
         headers {}
         current-header-name nil]
    (if-not line
      {:headers headers}
      (let [[k v] (str/split line #":")
            header-name (str/lower-case k)]
        (if v
          (recur rest
                 (update-in headers [header-name] #(if % (str % "," (str/trim v)) (str/trim v)))
                 header-name)
          (if-not current-header-name
            (throw (ex-info "Cannot parse headers."
                            {:lines lines}))
            (recur rest
                   (update-in headers [current-header-name] #(str % "," (str/trim k)))
                   current-header-name)))))))

(defn parse-request
  "Parse the string and return a ring-like request object.

  I can't use a proper HTTP parser since some of the files are
  deliberately broken or not even parsed properly by their test
  suite (e.g. multiple line headers)."
  [s]
  (let [[request-line & rest] (with-open [rdr (io/reader (-> (.getBytes s)
                                                             (ByteArrayInputStream.)
                                                             (BOMInputStream.)))]
                                (into [] (line-seq rdr)))
        [headers [_empty-line & rest]] (split-with (complement empty?) rest)
        body (str/join "\n" rest)]
    (merge {:body (.getBytes ^String body "UTF-8")}
           (parse-request-line request-line)
           (parse-headers headers))))

(def suffix-handlers
  {"req"   [:request           parse-request]
   "creq"  [:canonical-request identity]
   "sts"   [:string-to-sign    identity]
   "authz" [:authorization     identity]
   "sreq"  [:signed-request    parse-request]})

(defn suffix
  [f]
  (last (str/split (.getName f) #"\.")))

(defn sub-directories
  [dir]
  (let [children (->> dir (.listFiles) (filter #(.isDirectory %)))]
    (into children
          (mapcat sub-directories children))))

(defn read-tests
  [dir]
  (->> (sub-directories dir)
       (remove #(exclude-dir? (.getName %)))
       (map (fn [test-directory]
              (reduce #(let [[kw parser] (suffix-handlers (suffix %2))]
                         (assoc %1 kw (parser (slurp %2))))
                      {:name (.getName test-directory)}
                      (->> (.listFiles test-directory)
                           (remove #(.isDirectory %))))))))

(def credentials
  {:aws/access-key-id "AKIDEXAMPLE"
   :aws/secret-access-key "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"})

(deftest test-aws-sign-v4
  (doseq [{:keys [name request authorization]} (read-tests (io/file (io/resource "aws-sig-v4-test-suite")))]
    (testing name
      (testing "using endpointPrefix"
        (let [service        {:metadata {:signatureVersion "v4"
                                         :endpointPrefix   "service"
                                         :uid              "service-2018-12-28"}}
              signed-request (signers/sign-http-request service {:region "us-east-1"} credentials request)]
          (is (= (get-in signed-request [:headers "authorization"])
                 authorization))))
      (testing "using signingName"
        (let [service        {:metadata {:signatureVersion "v4"
                                         :endpointPrefix   "incorrect"
                                         :signingName      "service"
                                         :uid              "service-2018-12-28"}}
              signed-request (signers/sign-http-request service {:region "us-east-1"} credentials request)]
          (is (= (get-in signed-request [:headers "authorization"])
                 authorization)))))))

(deftest test-canonical-query-string
  (testing "ordering"
    (is (= "q=Red&q.parser=lucene"
           (#'signers/canonical-query-string {:query-string "q=Red&q.parser=lucene"})
           (#'signers/canonical-query-string {:query-string "q.parser=lucene&q=Red"})
           (#'signers/canonical-query-string {:uri "path?q=Red&q.parser=lucene"})
           (#'signers/canonical-query-string {:uri "path?q.parser=lucene&q=Red"}))))
  (testing "key with no value"
    (is (= "policy=" (#'signers/canonical-query-string {:uri "my-bucket?policy"})))))

(s/fdef signers/uri-encode
  :args (s/cat :string (s/and string? not-empty) :extra-chars (s/? string?))
  :ret string?)

(deftest test-uri-encode
  (testing "with *unchecked-math* true"
    (set! *unchecked-math* true)
    (require '[cognitect.aws.signers] :reload)
    (let [res (first (stest/check `signers/uri-encode))]
      (is (true? (-> res :clojure.spec.test.check/ret :result))
          res)))

  (testing "with *unchecked-math* false (default)"
    (set! *unchecked-math* false)
    (require '[cognitect.aws.signers] :reload)
    (let [res (first (stest/check `signers/uri-encode))]
      (is (true? (-> res :clojure.spec.test.check/ret :result))
          res))))

(defspec aws-v4-signer-parity 1000
  (prop/for-all [service (g/gen-service "v4")
                 request g/gen-request]
    (let [signed-request (signers/sign-http-request service {:region "us-east-1"} credentials request)
          jdk-signed-request (jdk-v2/v4-jdk-signed-request service "us-east-1" credentials request)]
      (is (= (get-in signed-request [:headers "authorization"])
             (-> jdk-signed-request :headers :authorization))))))

(defspec aws-s3v4-signer-parity 1000
  (prop/for-all [service (g/gen-service "s3v4")
                 request g/gen-request]
    (let [signed-request (signers/sign-http-request service {:region "us-east-1"} credentials request)
          jdk-signed-request (jdk-v2/s3v4-jdk-signed-request service "us-east-1" credentials request)]
      (is (= (get-in signed-request [:headers "authorization"])
             (-> jdk-signed-request :headers :authorization))))))

(defspec aws-s3-signer-parity 1000
  (prop/for-all [service (g/gen-service "s3")
                 request g/gen-request]
    (let [signed-request (signers/sign-http-request service {:region "us-east-1"} credentials request)
          ;; https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingAWSSDK.html
          ;; If you are sending direct REST calls to Amazon S3, you must modify your application to use the Signature Version 4 signing process.
          jdk-signed-request (jdk-v2/s3v4-jdk-signed-request service "us-east-1" credentials request)]
      (is (= (get-in signed-request [:headers "authorization"])
             (-> jdk-signed-request :headers :authorization))))))

(comment
  (t/run-tests)

  (require '[clojure.spec.gen.alpha :as gen])

  (defn gen-request
    "Gen a request with a non-empty body"
    []
    (let [body (->> (gen/string) gen/sample (filter seq) first)]
      (-> g/gen-request gen/sample first (assoc :body (.getBytes ^String body "UTF-8")))))

  ;; ======================================================================
  ;; V4 testing
  (def my-service (-> (g/gen-service "v4") gen/sample first))
  (def my-request (gen-request))
  (def my-jdk-v2-signed-request (jdk-v2/v4-jdk-signed-request my-service "us-east-1" credentials my-request))
  (-> my-jdk-v2-signed-request :headers)
  (-> (signers/sign-http-request my-service {:region "us-east-1"} credentials my-request) :headers)
  ;; ======================================================================
  ;; S3V4 testing
  (def my-s3-service (-> (g/gen-service "s3v4") gen/sample first))
  (def my-s3-request (gen-request))
  (def my-s3-jdk-v2-signed-request (jdk-v2/s3v4-jdk-signed-request my-s3-service "us-east-1" credentials my-s3-request))
  (-> my-s3-jdk-v2-signed-request :headers)
  (-> (signers/sign-http-request my-s3-service {:region "us-east-1"} credentials my-s3-request) :headers)
  ;; ======================================================================

  (sub-directories (io/file (io/resource "aws-sig-v4-test-suite")))

  (read-tests (io/file (io/resource "aws-sig-v4-test-suite"))))
