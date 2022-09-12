;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.client.protocol
  "Impl, don't call directly.")

(defprotocol Client
  (-get-info [_] "Used by fns in cognitect.aws.client.api. Implementors must supply
                  :service, and may also supply anything else needed by the other
                  protocol functions.

                  The cognitect.aws.client.api/client uses the following:
                    :retriable?
                    :backoff
                    :http-client
                    :endpoint-provider
                    :region-provider
                    :credentials-provider
                    :validate-requests?")
  (-invoke [this op-map] "Packages and ships a request and returns a response")
  (-invoke-async [this op-map] "Packages and ships a request and returns a channel that will contain a response")
  (-stop [this] "Release resources managed by this client"))