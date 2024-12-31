(ns cognitect.aws.client.shared-test
  (:require [clojure.test :as t :refer [deftest is testing]]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.client.shared :as shared]
            [cognitect.aws.http :as http]))

(deftest test-shared-delays-are-not-realized-unnecessarily
  ;; issue 262
  (testing "Creating a client with custom attributes does not deref the default delayed shared resources."
    ;; As a precondition of this test, the delays must be unrealized.
    (require 'cognitect.aws.client.shared :reload)
    (do
      (aws/client {:api :s3
                   :http-client (reify http/HttpClient)
                   :region-provider :test-provider
                   :credentials-provider :test-provider})
      (is (not (realized? @#'shared/shared-http-client)))
      (is (not (realized? @#'shared/shared-region-provider)))
      (is (not (realized? @#'shared/shared-credentials-provider))))))
