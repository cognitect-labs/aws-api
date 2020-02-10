;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(require '[clojure.data.json :as json]
         '[cognitect.aws.client.api :as aws]
         '[cognitect.aws.credentials :as credentials])

(def iam (aws/client {:api :iam}))

(->> (aws/invoke iam {:op :ListRoles}) :Roles (map :RoleName))

;; who am I?
(aws/invoke iam {:op :GetUser})

(def me (:User *1))

;; make a role to use for this example
(aws/invoke iam {:op      :CreateRole
                 :request {:RoleName "aws-api-example-role"
                           :AssumeRolePolicyDocument
                           (json/json-str
                            {"Version"   "2012-10-17",
                             "Statement" [ {"Effect"    "Allow"
                                            "Principal" {"AWS" [(:Arn me)]}
                                            "Action"    ["sts:AssumeRole"]}]})}})

(def new-role (:Role *1))

;; make a policy to use for this example
(aws/invoke iam {:op      :CreatePolicy
                 :request {:PolicyName "IAMGetMe"
                           :PolicyDocument
                           (json/json-str
                            {"Version"   "2012-10-17",
                             "Statement" [ {"Effect"   "Allow"
                                            "Action"   ["iam:GetUser"]
                                            "Resource" [(:Arn me)]}]})}})

(def policy (:Policy *1))

;; attach the new policy to the new role
(aws/invoke iam {:op :AttachRolePolicy :request {:RoleName (:RoleName new-role)
                                                 :PolicyArn (:Arn policy)}})

;; make a credentials provider that can assume a role
(defn assumed-role-credentials-provider [role-arn session-name refresh-every-n-seconds]
  (let [sts (aws/client {:api :sts})]
    (credentials/cached-credentials-with-auto-refresh
     (reify credentials/CredentialsProvider
       (fetch [_]
         (when-let [creds (:Credentials
                           (aws/invoke sts
                                       {:op      :AssumeRole
                                        :request {:RoleArn         role-arn
                                                  :RoleSessionName session-name}}))]
           {:aws/access-key-id     (:AccessKeyId creds)
            :aws/secret-access-key (:SecretAccessKey creds)
            :aws/session-token     (:SessionToken creds)
            ::credentials/ttl      refresh-every-n-seconds}))))))

(def provider (assumed-role-credentials-provider (:Arn new-role) "example-session" 600))

;; make a client using the assumed role credentials provider
(def iam-with-assumed-role (aws/client {:api :iam :credentials-provider provider}))

;; use it!
(aws/invoke iam-with-assumed-role {:op :GetUser :request {:UserName (:UserName me)}})

;; clean up
(credentials/stop provider)
(aws/invoke iam {:op :DetachRolePolicy :request {:RoleName (:RoleName new-role) :PolicyArn (:Arn policy)}})
(aws/invoke iam {:op :DeletePolicy :request {:PolicyArn (:Arn policy)}})
(aws/invoke iam {:op :DeleteRole :request {:RoleName "aws-api-example-role"}})
