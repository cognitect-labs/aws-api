;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(require '[clojure.data.json :as json]
         '[cognitect.aws.client.api :as aws]
         '[cognitect.aws.credentials :as credentials])

(def iam (aws/client {:api :iam}))

(->> (aws/invoke iam {:op :ListRoles}) :Roles (map :RoleName) sort)

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

(def new-role (:Role (aws/invoke iam {:op      :GetRole
                                      :request {:RoleName "aws-api-example-role"}})))

;; make a policy to use for this example
(aws/invoke iam {:op      :CreatePolicy
                 :request {:PolicyName "IAMGetMe"
                           :PolicyDocument
                           (json/json-str
                            {"Version"   "2012-10-17",
                             "Statement" [ {"Effect"   "Allow"
                                            "Action"   ["iam:GetUser"]
                                            "Resource" [(:Arn me)]}]})}})

(def policy (->> (aws/invoke iam {:op :ListPolicies})
                 :Policies
                 (filter #(re-find #"IAMGetMe" (:Arn %)))
                 first))

;; attach the new policy to the new role
(aws/invoke iam {:op :AttachRolePolicy :request {:RoleName (:RoleName new-role)
                                                 :PolicyArn (:Arn policy)}})

;; make a credentials provider that can assume a role
(defn assumed-role-credentials-provider [role-arn]
  (let [sts (aws/client {:api :sts})]
    (credentials/cached-credentials-with-auto-refresh
     (reify credentials/CredentialsProvider
       (fetch [_]
         (when-let [creds (:Credentials
                           (aws/invoke sts
                                       {:op      :AssumeRole
                                        :request {:RoleArn         role-arn
                                                  :RoleSessionName (str (gensym "example-session-"))}}))]
           {:aws/access-key-id     (:AccessKeyId creds)
            :aws/secret-access-key (:SecretAccessKey creds)
            :aws/session-token     (:SessionToken creds)
            ::credentials/ttl      (credentials/calculate-ttl creds)}))))))

(def provider (assumed-role-credentials-provider (:Arn new-role)))

;; make a client using the assumed role credentials provider
(def iam-with-assumed-role (aws/client {:api :iam :credentials-provider provider}))

;; use it!
(aws/invoke iam-with-assumed-role {:op :GetUser :request {:UserName (:UserName me)}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; let's try that with s3

(aws/invoke iam {:op      :CreatePolicy
                 :request {:PolicyName "AssumeRoleS3ExamplePolicy"
                           :PolicyDocument
                           (json/json-str
                            {"Version"   "2012-10-17",
                             "Statement" [ {"Effect"   "Allow"
                                            "Action" ["*"]
                                            "Resource" ["arn:aws:s3:::*"]}]})}})

(def bucket-policy (->> (aws/invoke iam {:op :ListPolicies})
                        :Policies
                        (filter #(re-find #"AssumeRoleS3ExamplePolicy" (:Arn %)))
                        first))

(aws/invoke iam {:op :AttachRolePolicy :request {:RoleName (:RoleName new-role)
                                                 :PolicyArn (:Arn bucket-policy)}})

;; assuming you have permissions to do this already:
(aws/invoke iam {:api :s3
                 :op  :ListBuckets})

(def s3-with-assumed-role (aws/client {:api :s3 :credentials-provider provider}))

(aws/invoke s3-with-assumed-role {:op  :ListBuckets})

;; and now with a presigned URL!

(def list-buckets-url
  (:presigned-url (aws/invoke s3-with-assumed-role
                              {:op            :ListBuckets
                               :workflow      :cognitect.aws.alpha.workflow/presigned-url
                               :presigned-url {:expires 60}})))

(defn curl [url] (clojure.java.shell/sh "curl" url))

;; you can curl it
(curl list-buckets-url)

;; or you can use aws-api to fetch it
(aws/invoke s3-with-assumed-role
            {:workflow      :cognitect.aws.alpha.workflow/fetch-presigned-url
             :presigned-url {:url list-buckets-url}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; clean up
(aws/invoke iam {:op :DetachRolePolicy :request {:RoleName  (:RoleName new-role)
                                                 :PolicyArn (:Arn bucket-policy)}})
(aws/invoke iam {:op :DetachRolePolicy :request {:RoleName  (:RoleName new-role)
                                                 :PolicyArn (:Arn policy)}})
(aws/invoke iam {:op :DeletePolicy :request {:PolicyArn (:Arn bucket-policy)}})
(aws/invoke iam {:op :DeletePolicy :request {:PolicyArn (:Arn policy)}})
(aws/invoke iam {:op :DeleteRole :request {:RoleName "aws-api-example-role"}})
