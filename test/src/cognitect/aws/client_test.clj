(ns cognitect.aws.client-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [clojure.core.async :as a]
            [matcher-combinators.test]
            [cognitect.aws.credentials :as credentials]
            [cognitect.aws.diagnostics :as diagnostics]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.client :as client]
            [cognitect.aws.client.shared :as shared]
            [cognitect.aws.http :as http]
            [cognitect.aws.region :as region]
            [cognitect.aws.credentials :as creds]
            [cognitect.aws.flow :as flow]
            [cognitect.aws.flow.default-stack :as default-stack]))

(defn stub-http-client [result]
  (reify http/HttpClient
    (-submit [_ _ ch]
      (a/go (a/>! ch result))
      ch)
    (-stop [_])))

(defn stub-credentials-provider [creds]
  (reify creds/CredentialsProvider
    (fetch [_] creds)))

(defn stub-region-provider [region]
  (reify region/RegionProvider
    (fetch [_] region)))

(def params {:api                  :s3
             ;; use an anomaly to bypass parsing http-response
             :http-client          (stub-http-client {:cognitect.anomalies/category :cognitect.aws/test
                                                      :cognitect.anomalies/message  "test"})
             :region-provider      (stub-region-provider :us-east-1)
             :credentials-provider (stub-credentials-provider
                                    {:aws/access-key-id     "a"
                                     :aws/secret-access-key "b"})})

(deftest test-request-meta
  (let [res (aws/invoke (aws/client params) {:op :ListBuckets})]
    (testing "includes :http-request"
      (is (=  {:uri "/"
               :server-name "s3.amazonaws.com"
               :body nil}
              (select-keys (:http-request (meta res)) [:uri :server-name :body]))))
    (testing "includes raw response"
      (is (= {:cognitect.anomalies/category :cognitect.aws/test,
              :cognitect.anomalies/message "test"}
             (:http-response (meta res)))))
    (testing "includes flow log"
      (is (contains? (meta res) ::flow/log)))))

(deftest test-credentials-resolution
  (testing "uses shared credentials-provider by default"
    (let [c     (aws/client {})
          creds {:aws/access-key-id     "aki"
                 :aws/secret-access-key "sak"}]
      (with-redefs [shared/credentials-provider #(stub-credentials-provider creds)]
        (is (match? creds
                    (:credentials (aws/invoke c {:steps
                                                 [default-stack/add-credentials-provider
                                                  default-stack/provide-credentials]})))))))
  (testing "uses credentials-provider provided to client"
    (let [creds {:aws/access-key-id     "aki"
                 :aws/secret-access-key "sak"}
          c     (aws/client {:credentials-provider (stub-credentials-provider creds)})]
      (is (match? creds
                  (:credentials (aws/invoke c {:steps
                                               [default-stack/add-credentials-provider
                                                default-stack/provide-credentials]}))))))
  ;; TODO:(dchelimsky,2020-05-04) client supports :region but not :credentials.
  ;; Consider supporting credentials as well.
  (testing "nil creds (regression test - should not hang)"
    (let [c (aws/client {:credentials-provider (stub-credentials-provider nil)})]
      (is (re-find #"^Unable to fetch credentials"
                   (:cognitect.anomalies/message
                    (aws/invoke c {:steps
                                   [default-stack/add-credentials-provider
                                    default-stack/provide-credentials]}))))))
  (testing "empty creds (regression test - should not hang)"
    (let [c (aws/client {:credentials-provider (stub-credentials-provider {})})]
      (is (re-find #"^Unable to fetch credentials"
                   (:cognitect.anomalies/message
                    (aws/invoke c {:steps
                                   [default-stack/add-credentials-provider
                                    default-stack/provide-credentials]})))))))

(testing "uses region provided to invoke"
  (let [c (aws/client {:region-provider (region/basic-region-provider "a-region")})]
    (is (= "another-region"
           (:region (aws/invoke c {:region "another-region"
                                   :steps
                                   [default-stack/add-region-provider
                                    default-stack/provide-region]}))))))

(deftest test-region-resolution
  (testing "uses shared region-provider by default"
    (let [c (aws/client {})]
      (with-redefs [shared/region-provider #(region/basic-region-provider "a-region")]
        (is (= "a-region"
               (:region (aws/invoke c {:steps
                                       [default-stack/add-region-provider
                                        default-stack/provide-region]})))))))
  (testing "uses region supplied to client"
    (let [c (aws/client {:region "a-region"})]
      (is (= "a-region"
             (:region (aws/invoke c {:steps
                                     [default-stack/add-region-provider
                                      default-stack/provide-region]}))))))
  (testing "uses region-provider supplied to client"
    (let [c (aws/client {:region-provider (region/basic-region-provider "a-region")})]
      (is (= "a-region"
             (:region (aws/invoke c {:steps
                                     [default-stack/add-region-provider
                                      default-stack/provide-region]}))))))
  (testing "uses region provided to invoke"
    (let [c (aws/client {:region-provider (region/basic-region-provider "a-region")})]
      (is (= "another-region"
             (:region (aws/invoke c {:region "another-region"
                                     :steps
                                     [default-stack/add-region-provider
                                      default-stack/provide-region]}))))))
  (testing "anomaly when region is nil (regression test - should not hang)"
    (let [c (aws/client (assoc params
                               :region-provider
                               (stub-region-provider nil)))]
      (is (re-find #"^Unable to fetch region"
                   (:cognitect.anomalies/message
                    (aws/invoke c {:steps
                                   [default-stack/add-region-provider
                                    default-stack/provide-region]}))))))
  (testing "anomaly when region is empty (regression test - should not hang)"
    (let [c (aws/client (assoc params
                               :region-provider
                               (stub-region-provider "")))]
      (is (re-find #"^No known endpoint."
                   (:cognitect.anomalies/message
                    (aws/invoke c {:steps
                                   [default-stack/add-region-provider
                                    default-stack/provide-region
                                    default-stack/add-endpoint-provider
                                    default-stack/provide-endpoint]})))))))

(def sync-anomaly-step
  {:name "sync anomaly"
   :f (fn [context]
        (assoc context :cognitect.anomalies/category :cognitect.anomalies/incorrect))})

(def async-anomaly-step
  {:name "async anomaly"
   :f (fn [{:keys [executor] :as context}]
        (flow/submit executor
                     #(assoc context :cognitect.anomalies/category :cognitect.anomalies/incorrect)))})

(def sync-error-step
  {:name "sync error"
   :f (fn [_] (throw (ex-info "sync error" {})))})

(def async-error-step
  {:name "async error"
   :f (fn [{:keys [executor] :as context}]
        (flow/submit executor (fn [] (throw (ex-info "async error" {})))))})

(defn step-named [n]
  {:name n :f identity})

(def c (aws/client {}))

(deftest short-circuit-on-anomaly
  (let [c (aws/client {})]
    (is (match? [{:name "before anomaly"}
                 {:name "sync anomaly"}]
                (diagnostics/summarize-log
                 (aws/invoke c {:steps
                                [(step-named "before anomaly")
                                 sync-anomaly-step
                                 (step-named "after anomaly")]}))))

    (is (match? [{:name "before anomaly"}
                 {:name "async anomaly"}]
                (diagnostics/summarize-log
                 (aws/invoke c {:steps
                                [(step-named "before anomaly")
                                 async-anomaly-step
                                 (step-named "after anomaly")]}))))

    (is (match? [{:name "before error"}
                 {:name "sync error"}]
                (diagnostics/summarize-log
                 (aws/invoke c {:steps
                                [(step-named "before error")
                                 sync-error-step
                                 (step-named "after error")]}))))

    (is (match? [{:name "before error"}
                 {:name "async error"}]
                (diagnostics/summarize-log
                 (aws/invoke c {:steps
                                [(step-named "before error")
                                 async-error-step
                                 (step-named "after error")]}))))))
