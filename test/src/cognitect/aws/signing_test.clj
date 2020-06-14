;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.signing-test
  "See http://docs.aws.amazon.com/general/latest/gr/signature-v4-test-suite.html"
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cognitect.aws.client :as client]
            [cognitect.aws.signing :as signing]
            [cognitect.aws.signing.impl :as signing.impl]
            [cognitect.aws.util :as util]
            [cognitect.aws.test.utils :as test.utils])
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
        [headers [empty-line & rest]] (split-with (complement empty?) rest)
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

(defn- url-encode
  "The aws tests assume query strings are not already encoded, but aws-api
  encodes them before handing over to signers."
  [query-string]
  (some->> query-string
           (test.utils/query-string->vec)
           (reduce (fn [accum [k v]] (conj accum
                                           [(signing.impl/uri-encode k)
                                            (signing.impl/uri-encode v)]))
                   [])
           (util/query-string)))

(deftest test-aws-sign-v4
  (doseq [{:keys [name request authorization]} (read-tests (io/file (io/resource "aws-sig-v4-test-suite")))]
    (when (re-find #"get-vanilla" name)
      (testing name
        (testing "using endpointPrefix"
          (let [service        {:metadata {:signatureVersion "v4"
                                           :endpointPrefix   "service"
                                           :uid              "service-2018-12-28"}}
                signed-request (signing/sign-http-request
                                service {:region "us-east-1"} credentials
                                (update request :query-string url-encode))]
            (is (= authorization
                   (get-in signed-request [:headers "authorization"]))
                (str "Wrong signature for " request))))
        (testing "using signingName"
            (let [service        {:metadata {:signatureVersion "v4"
                                             :endpointPrefix   "incorrect"
                                             :signingName      "service"
                                             :uid              "service-2018-12-28"}}
                  signed-request (signing/sign-http-request
                                  service {:region "us-east-1"} credentials
                                  (update request :query-string url-encode))]
              (is (= authorization
                     (get-in signed-request [:headers "authorization"]))
                  (str "Wrong signature for " request))))))))

(deftest test-canonical-query-string
  (testing "ordering"
    (is (= "q=Red&q.parser=lucene"
           (#'signing.impl/canonical-query-string {:query-string "q=Red&q.parser=lucene"})
           (#'signing.impl/canonical-query-string {:query-string "q.parser=lucene&q=Red"}))))
  (testing "key with no value"
    (is (= "policy=" (#'signing.impl/canonical-query-string {:query-string "policy="})))))

(s/fdef signing.impl/uri-encode
  :args (s/cat :string (s/and string? (complement str/blank?)) :extra-chars (s/? string?))
  :ret string?)

(deftest test-uri-encode
  (testing "regression for issue-71"
    ;; See https://github.com/cognitect-labs/aws-api/issues/71
    (testing "with *unchecked-math* true"
      (binding [*unchecked-math* true]
        (require '[cognitect.aws.signing.impl :as signing.impl] :reload)
        (let [res (:clojure.spec.test.check/ret (first (stest/check `signing.impl/uri-encode)))]
          (is (true? (:result res))
              res))))

    (testing "with *unchecked-math* false"
      (binding [*unchecked-math* false]
        (require '[cognitect.aws.signing.impl :as signing.impl] :reload)
        (let [res (:clojure.spec.test.check/ret (first (stest/check `signing.impl/uri-encode)))]
          (is (true? (:result res))
              res))))))

(deftest test-presign-url
  ;; "Golden Master" test
  (is (= "https://examplebucket.s3.amazonaws.com/a/path?Action=GetSomething&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AWSKeyId%2F20130524%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-Signature=8beb5653f122f71ae1d068f69823ca374d247d1e5fedb9a5c0583ba75bf958ab&X-Amz-SignedHeaders=host"
         (:presigned-url
          (signing/presigned-url
           {:http-request  {:request-method :get
                            :server-name    "examplebucket.s3.amazonaws.com"
                            :uri            "/a/path"
                            :headers        {"x-amz-date" "20130524T000000Z"
                                             "host"       "examplebucket.s3.amazonaws.com"}}
            :op            :GetSomething
            :presigned-url {:expires 86400}
            :service       {:metadata {:signingName "s3"}}
            :endpoint      {:region "us-east-1"}
            :credentials   {:aws/access-key-id     "AWSKeyId"
                            :aws/secret-access-key "AWSSecretAccessKey"}})))))

(comment
  (t/run-tests)

  (sub-directories (io/file (io/resource "aws-sig-v4-test-suite")))

  (read-tests (io/file (io/resource "aws-sig-v4-test-suite")))

  )
