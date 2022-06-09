(ns cognitect.aws.interceptors-test
  (:require [clojure.test :refer [deftest is]]
            [cognitect.aws.interceptors :as interceptors]))

(deftest test-apigatewaymanagementapi
  (is (= "prefixsuffix"
         (:uri
          (interceptors/modify-http-request {:metadata {:uid "apigatewaymanagementapi"}}
                                            {:op :PostToConnection
                                             :request {:ConnectionId "suffix"}}
                                            {:uri "prefix"})))))