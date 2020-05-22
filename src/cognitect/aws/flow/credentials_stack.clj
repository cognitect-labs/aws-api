(ns cognitect.aws.flow.credentials-stack
  (:require [cognitect.aws.client.shared :as shared]
            [cognitect.aws.flow :as flow]
            [cognitect.aws.credentials :as credentials]))

(defn- prepend-queue [q items]
  (into (into clojure.lang.PersistentQueue/EMPTY items)
        q))

(defn- provider-step
  ([name cached-step provider]
   (provider-step name cached-step provider nil))
  ([name cached-step provider report-anomaly?]
   {:name name
    :f
    (fn [context]
      (if (:credentials context)
        context
        (if-let [creds (credentials/valid-credentials (credentials/fetch provider))]
          (do
            ;; TODO (dchelimsky,2020-05-22): consider optimization of
            ;; dequeuing any subsequent credential provider steps.
            (when-not @cached-step (reset! cached-step (provider-step name cached-step provider)))
            (assoc context :credentials creds))
          (if report-anomaly?
            {:cognitect.anomalies/category :cognitect.anomalies/incorrect
             :cognitect.anomalies/message "Unable to find credentials"}
            context))))}))

(defn process-credentials []
  (let [cached-step (atom nil)]
    {:name "process credentials"
     :f (fn [{:keys [http-client credentials-provider] :as context}]
          (update context
                  ::flow/queue
                  prepend-queue
                  (cond credentials-provider
                        [(provider-step "user provided credentials provider" cached-step
                                        credentials-provider
                                        :report-anomaly!)]

                        @cached-step
                        [@cached-step]

                        :else
                        [(provider-step "environment credentials provider" cached-step
                                        (credentials/environment-credentials-provider))
                         (provider-step "system properties credentials provider" cached-step
                                        (credentials/system-property-credentials-provider))
                         (provider-step "profile credentials provider" cached-step
                                        (credentials/profile-credentials-provider))
                         (provider-step "container credentials provider" cached-step
                                        (credentials/container-credentials-provider http-client))
                         (provider-step "instance profile credentials provider" cached-step
                                        (credentials/instance-profile-credentials-provider http-client)
                                        :report-anomaly!)])))}))

(comment

  (def c (cognitect.aws.client.api/client {}))

  (def steps [(process-credentials)
              {:name "hide password"
               :f (fn [context]
                    (if (:credentials context)
                      (assoc-in context [:credentials :aws/secret-access-key] "REDACTED")
                      context))}])

  (cognitect.aws.client.api/invoke c {:workflow-steps steps})

  (cognitect.aws.client.api/invoke c {:credentials-provider
                                      (credentials/basic-credentials-provider
                                       {:access-key-id "id"
                                        :secret-access-key "secret"})
                                      :workflow-steps steps})

  (cognitect.aws.client.api/invoke c {:credentials-provider
                                      (reify credentials/CredentialsProvider
                                        (fetch [_]))
                                      :workflow-steps steps})

  (def res *1)

  (cognitect.aws.diagnostics/summarize-log res)

  (cognitect.aws.diagnostics/trace-key res :credentials)

  )
