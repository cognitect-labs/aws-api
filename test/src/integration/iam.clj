(ns integration.iam
  (:require [clojure.test :refer :all]
            [cognitect.aws.client.api :as aws]
            [integration.aux :as aux]
            [clojure.data.json :as json])
  (:import (java.time Instant)))

(use-fixtures :each aux/ensure-test-profile)

(defn role-exists?
  [iam role-name]
  (contains?
    (aws/invoke iam {:op      :GetRole
                     :request {:RoleName role-name}})
    :Role))

(deftest ^:integration iam-test
  (let [iam (aws/client {:api :iam})
        role-name (str "aws-api-test-role" (.getEpochSecond (Instant/now)))]
    (testing ":ListRoles"
      (is (contains?
            (aws/invoke iam {:op :ListRoles})
            :Roles)))

    (testing ":GetUser"
      (is (contains?
            (aws/invoke iam {:op :GetUser})
            :User)))

    (testing ":CreateRole"
      (aws/invoke iam {:op      :CreateRole
                       :request {:RoleName role-name
                                 :AssumeRolePolicyDocument
                                 (json/json-str
                                   {"Version"   "2012-10-17",
                                    "Statement" [{"Effect"    "Allow"
                                                  "Principal" {"AWS" [(:Arn (:User (aws/invoke iam {:op :GetUser})))]}
                                                  "Action"    ["sts:AssumeRole"]}]})}})
      (is (role-exists? iam role-name)))

    (testing ":DeleteRole"
      (aws/invoke iam {:op :DeleteRole :request {:RoleName role-name}})
      (is (not (role-exists? iam role-name))))))
