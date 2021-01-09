{:namespaces
 ({:doc
   "API functions for using a client to interact with AWS services.",
   :name "cognitect.aws.client.api",
   :wiki-url "cognitect.aws.client.api-api.html",
   :source-url nil}
  {:doc nil,
   :name "cognitect.aws.client.shared",
   :wiki-url "cognitect.aws.client.shared-api.html",
   :source-url nil}
  {:doc
   "Contains credentials providers and helpers for discovering credentials.\n\nAlpha. Subject to change.",
   :name "cognitect.aws.credentials",
   :wiki-url "cognitect.aws.credentials-api.html",
   :source-url nil}
  {:doc
   "Region providers. Primarily for internal use, and subject to change.",
   :name "cognitect.aws.region",
   :wiki-url "cognitect.aws.region-api.html",
   :source-url nil}
  {:doc nil,
   :name "cognitect.aws.retry",
   :wiki-url "cognitect.aws.retry-api.html",
   :source-url nil}
  {:doc
   "API functions for using a client to interact with AWS services.",
   :name "cognitect.aws.client.api.async",
   :wiki-url "cognitect.aws.client.api.async-api.html",
   :source-url nil}),
 :vars
 ({:raw-source-url nil,
   :name "client",
   :file "src/cognitect/aws/client/api.clj",
   :source-url nil,
   :line 26,
   :var-type "function",
   :arglists
   ([{:keys
      [api
       region
       region-provider
       retriable?
       backoff
       credentials-provider
       endpoint-override
       http-client],
      :or {endpoint-override {}}}]),
   :doc
   "Given a config map, create a client for specified api. Supported keys:\n\n:api                  - required, this or api-descriptor required, the name of the api\n                        you want to interact with e.g. :s3, :cloudformation, etc\n:http-client          - optional, to share http-clients across aws-clients.\n                        See default-http-client.\n:region-provider      - optional, implementation of aws-clojure.region/RegionProvider\n                        protocol, defaults to cognitect.aws.region/default-region-provider.\n                        Ignored if :region is also provided\n:region               - optional, the aws region serving the API endpoints you\n                        want to interact with, defaults to region provided by\n                        by the region-provider\n:credentials-provider - optional, implementation of\n                        cognitect.aws.credentials/CredentialsProvider\n                        protocol, defaults to\n                        cognitect.aws.credentials/default-credentials-provider\n:endpoint-override    - optional, map to override parts of the endpoint. Supported keys:\n                          :protocol     - :http or :https\n                          :hostname     - string\n                          :port         - int\n                          :path         - string\n                        If the hostname includes an AWS region, be sure use the same\n                        region for the client (either via out of process configuration\n                        or the :region key supplied to this fn).\n                        Also supports a string representing just the hostname, though\n                        support for a string is deprectated and may be removed in the\n                        future.\n:retriable?           - optional, fn of http-response (see cognitect.aws.http/submit).\n                        Should return a boolean telling the client whether or\n                        not the request is retriable.  The default,\n                        cognitect.aws.retry/default-retriable?, returns\n                        true when the response indicates that the service is\n                        busy or unavailable.\n:backoff              - optional, fn of number of retries so far. Should return\n                        number of milliseconds to wait before the next retry\n                        (if the request is retriable?), or nil if it should stop.\n                        Defaults to cognitect.aws.retry/default-backoff.\n\nBy default, all clients use shared http-client, credentials-provider, and\nregion-provider instances which use a small collection of daemon threads.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.client.api",
   :wiki-url
   "/cognitect.aws.client.api-api.html#cognitect.aws.client.api/client"}
  {:raw-source-url nil,
   :name "default-http-client",
   :file "src/cognitect/aws/client/api.clj",
   :source-url nil,
   :line 108,
   :var-type "function",
   :arglists ([]),
   :doc
   "Create an http-client to share across multiple aws-api clients.",
   :namespace "cognitect.aws.client.api",
   :wiki-url
   "/cognitect.aws.client.api-api.html#cognitect.aws.client.api/default-http-client"}
  {:raw-source-url nil,
   :name "doc",
   :file "src/cognitect/aws/client/api.clj",
   :source-url nil,
   :line 208,
   :var-type "function",
   :arglists ([client operation]),
   :doc
   "Given a client and an operation (keyword), prints documentation\nfor that operation to the current value of *out*. Returns nil.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.client.api",
   :wiki-url
   "/cognitect.aws.client.api-api.html#cognitect.aws.client.api/doc"}
  {:raw-source-url nil,
   :name "doc-str",
   :file "src/cognitect/aws/client/api.clj",
   :source-url nil,
   :line 172,
   :var-type "function",
   :arglists
   ([{:keys
      [documentation documentationUrl request required response refs],
      :as doc}]),
   :doc
   "Given data produced by `ops`, returns a string\nrepresentation.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.client.api",
   :wiki-url
   "/cognitect.aws.client.api-api.html#cognitect.aws.client.api/doc-str"}
  {:raw-source-url nil,
   :name "invoke",
   :file "src/cognitect/aws/client/api.clj",
   :source-url nil,
   :line 113,
   :var-type "function",
   :arglists ([client op-map]),
   :doc
   "Package and send a request to AWS and return the result.\n\nSupported keys in op-map:\n\n:op                   - required, keyword, the op to perform\n:request              - required only for ops that require them.\n:retriable?           - optional, defaults to :retriable? on the client.\n                        See client.\n:backoff              - optional, defaults to :backoff on the client.\n                        See client.\n\nAfter invoking (cognitect.aws.client.api/validate-requests true), validates\n:request in op-map.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.client.api",
   :wiki-url
   "/cognitect.aws.client.api-api.html#cognitect.aws.client.api/invoke"}
  {:raw-source-url nil,
   :name "ops",
   :file "src/cognitect/aws/client/api.clj",
   :source-url nil,
   :line 162,
   :var-type "function",
   :arglists ([client]),
   :doc
   "Returns a map of operation name to operation data for this client.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.client.api",
   :wiki-url
   "/cognitect.aws.client.api-api.html#cognitect.aws.client.api/ops"}
  {:raw-source-url nil,
   :name "request-spec-key",
   :file "src/cognitect/aws/client/api.clj",
   :source-url nil,
   :line 141,
   :var-type "function",
   :arglists ([client op]),
   :doc
   "Returns the key for the request spec for op.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.client.api",
   :wiki-url
   "/cognitect.aws.client.api-api.html#cognitect.aws.client.api/request-spec-key"}
  {:raw-source-url nil,
   :name "response-spec-key",
   :file "src/cognitect/aws/client/api.clj",
   :source-url nil,
   :line 148,
   :var-type "function",
   :arglists ([client op]),
   :doc
   "Returns the key for the response spec for op.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.client.api",
   :wiki-url
   "/cognitect.aws.client.api-api.html#cognitect.aws.client.api/response-spec-key"}
  {:raw-source-url nil,
   :name "stop",
   :file "src/cognitect/aws/client/api.clj",
   :source-url nil,
   :line 217,
   :var-type "function",
   :arglists ([aws-client]),
   :doc
   "Has no effect when the underlying http-client is the shared\ninstance.\n\nIf you explicitly provided any other instance of http-client, stops\nit, releasing resources.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.client.api",
   :wiki-url
   "/cognitect.aws.client.api-api.html#cognitect.aws.client.api/stop"}
  {:raw-source-url nil,
   :name "validate-requests",
   :file "src/cognitect/aws/client/api.clj",
   :source-url nil,
   :line 132,
   :var-type "function",
   :arglists ([client] [client bool]),
   :doc
   "Given true, uses clojure.spec to validate all invoke calls on client.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.client.api",
   :wiki-url
   "/cognitect.aws.client.api-api.html#cognitect.aws.client.api/validate-requests"}
  {:raw-source-url nil,
   :name "credentials-provider",
   :file "src/cognitect/aws/client/shared.clj",
   :source-url nil,
   :line 27,
   :var-type "function",
   :arglists ([]),
   :doc
   "Returns the globally shared instance of credentials-provider, which\nuses the globally shared instance of http-client.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.client.shared",
   :wiki-url
   "/cognitect.aws.client.shared-api.html#cognitect.aws.client.shared/credentials-provider"}
  {:raw-source-url nil,
   :name "http-client",
   :file "src/cognitect/aws/client/shared.clj",
   :source-url nil,
   :line 19,
   :var-type "function",
   :arglists ([]),
   :doc
   "Returns the globally shared instance of http-client (created on the\nfirst call).\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.client.shared",
   :wiki-url
   "/cognitect.aws.client.shared-api.html#cognitect.aws.client.shared/http-client"}
  {:raw-source-url nil,
   :name "region-provider",
   :file "src/cognitect/aws/client/shared.clj",
   :source-url nil,
   :line 35,
   :var-type "function",
   :arglists ([]),
   :doc
   "Returns the globally shared instance of region-provider, which\nuses the globally shared instance of http-client.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.client.shared",
   :wiki-url
   "/cognitect.aws.client.shared-api.html#cognitect.aws.client.shared/region-provider"}
  {:raw-source-url nil,
   :name "auto-refreshing-credentials",
   :file "src/cognitect/aws/credentials.clj",
   :source-url nil,
   :line 107,
   :deprecated true,
   :var-type "function",
   :arglists ([provider] [provider scheduler]),
   :doc "Deprecated. Use cached-credentials-with-auto-refresh",
   :namespace "cognitect.aws.credentials",
   :wiki-url
   "/cognitect.aws.credentials-api.html#cognitect.aws.credentials/auto-refreshing-credentials"}
  {:raw-source-url nil,
   :name "basic-credentials-provider",
   :file "src/cognitect/aws/credentials.clj",
   :source-url nil,
   :line 324,
   :var-type "function",
   :arglists ([{:keys [access-key-id secret-access-key]}]),
   :doc
   "Given a map with :access-key-id and :secret-access-key,\nreturns an implementation of CredentialsProvider which returns\nthose credentials on fetch.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.credentials",
   :wiki-url
   "/cognitect.aws.credentials-api.html#cognitect.aws.credentials/basic-credentials-provider"}
  {:raw-source-url nil,
   :name "cached-credentials-with-auto-refresh",
   :file "src/cognitect/aws/credentials.clj",
   :source-url nil,
   :line 78,
   :var-type "function",
   :arglists ([provider] [provider scheduler]),
   :doc
   "Returns a CredentialsProvider which wraps `provider`, caching\ncredentials returned by `fetch`, and auto-refreshing the cached\ncredentials in a background thread when the credentials include a\n::ttl.\n\nCall `stop` to cancel future auto-refreshes.\n\nThe default ScheduledExecutorService uses a ThreadFactory that\nspawns daemon threads. You can override this by providing your own\nScheduledExecutorService.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.credentials",
   :wiki-url
   "/cognitect.aws.credentials-api.html#cognitect.aws.credentials/cached-credentials-with-auto-refresh"}
  {:raw-source-url nil,
   :name "calculate-ttl",
   :file "src/cognitect/aws/credentials.clj",
   :source-url nil,
   :line 248,
   :var-type "function",
   :arglists ([{:keys [Expiration], :as credentials}]),
   :doc
   "Primarily for internal use, returns time to live (ttl, in seconds),\nbased on `:Expiration` in credentials.  If `credentials` contains no\n`:Expiration`, defaults to 3600.\n\n`:Expiration` can be a string parsable by java.time.Instant/parse\n(returned by ec2/ecs instance credentials) or a java.util.Date\n(returned from :AssumeRole on aws sts client).",
   :namespace "cognitect.aws.credentials",
   :wiki-url
   "/cognitect.aws.credentials-api.html#cognitect.aws.credentials/calculate-ttl"}
  {:raw-source-url nil,
   :name "chain-credentials-provider",
   :file "src/cognitect/aws/credentials.clj",
   :source-url nil,
   :line 136,
   :var-type "function",
   :arglists ([providers]),
   :doc
   "Returns a credentials-provider which chains together multiple\ncredentials providers.\n\n`fetch` calls each provider in order until one returns a non-nil\nresult. This provider is then cached for future calls to `fetch`.\n\n`fetch` returns nil if none of the providers return credentials.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.credentials",
   :wiki-url
   "/cognitect.aws.credentials-api.html#cognitect.aws.credentials/chain-credentials-provider"}
  {:raw-source-url nil,
   :name "container-credentials-provider",
   :file "src/cognitect/aws/credentials.clj",
   :source-url nil,
   :line 265,
   :var-type "function",
   :arglists ([http-client]),
   :doc
   "For internal use. Do not call directly.\n\nReturn credentials from ECS iff one of\nAWS_CONTAINER_CREDENTIALS_RELATIVE_URI or\nAWS_CONTAINER_CREDENTIALS_FULL_URI is set.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.credentials",
   :wiki-url
   "/cognitect.aws.credentials-api.html#cognitect.aws.credentials/container-credentials-provider"}
  {:raw-source-url nil,
   :name "default-credentials-provider",
   :file "src/cognitect/aws/credentials.clj",
   :source-url nil,
   :line 306,
   :var-type "function",
   :arglists ([http-client]),
   :doc
   "Returns a chain-credentials-provider with (in order):\n\n  environment-credentials-provider\n  system-property-credentials-provider\n  profile-credentials-provider\n  container-credentials-provider\n  instance-profile-credentials-provider\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.credentials",
   :wiki-url
   "/cognitect.aws.credentials-api.html#cognitect.aws.credentials/default-credentials-provider"}
  {:raw-source-url nil,
   :name "environment-credentials-provider",
   :file "src/cognitect/aws/credentials.clj",
   :source-url nil,
   :line 163,
   :var-type "function",
   :arglists ([]),
   :doc
   "Return the credentials from the environment variables.\n\nLook at the following variables:\n* AWS_ACCESS_KEY_ID      required\n* AWS_SECRET_ACCESS_KEY  required\n* AWS_SESSION_TOKEN      optional\n\nReturns nil if any of the required variables is blank.\n\nLogs error if one required variable is blank but the other\nis not.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.credentials",
   :wiki-url
   "/cognitect.aws.credentials-api.html#cognitect.aws.credentials/environment-credentials-provider"}
  {:raw-source-url nil,
   :name "fetch",
   :file nil,
   :source-url nil,
   :var-type "function",
   :arglists ([provider]),
   :doc
   "Return the credentials found by this provider, or nil.\n\nCredentials should be a map with the following keys:\n\n:aws/access-key-id                      string  required\n:aws/secret-access-key                  string  required\n:aws/session-token                      string  optional\n:cognitect.aws.credentials/ttl          number  optional  Time-to-live in seconds",
   :namespace "cognitect.aws.credentials",
   :wiki-url
   "/cognitect.aws.credentials-api.html#cognitect.aws.credentials/fetch"}
  {:raw-source-url nil,
   :name "fetch-async",
   :file "src/cognitect/aws/credentials.clj",
   :source-url nil,
   :line 338,
   :var-type "function",
   :arglists ([provider]),
   :doc
   "Returns a channel that will produce the result of calling fetch on\nthe provider.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.credentials",
   :wiki-url
   "/cognitect.aws.credentials-api.html#cognitect.aws.credentials/fetch-async"}
  {:raw-source-url nil,
   :name "instance-profile-credentials-provider",
   :file "src/cognitect/aws/credentials.clj",
   :source-url nil,
   :line 285,
   :var-type "function",
   :arglists ([http-client]),
   :doc
   "For internal use. Do not call directly.\n\nReturn credentials from EC2 metadata service iff neither of\nAWS_CONTAINER_CREDENTIALS_RELATIVE_URI or\nAWS_CONTAINER_CREDENTIALS_FULL_URI\nis set.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.credentials",
   :wiki-url
   "/cognitect.aws.credentials-api.html#cognitect.aws.credentials/instance-profile-credentials-provider"}
  {:raw-source-url nil,
   :name "profile-credentials-provider",
   :file "src/cognitect/aws/credentials.clj",
   :source-url nil,
   :line 210,
   :var-type "function",
   :arglists ([] [profile-name] [profile-name f]),
   :doc
   "Return credentials in an AWS configuration profile.\n\nArguments:\n\nprofile-name  string  The name of the profile in the file. (default: default)\nf             File    The profile configuration file. (default: ~/.aws/credentials)\n\nhttps://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html\n  Parsed properties:\n\n  aws_access_key        required\n  aws_secret_access_key required\n  aws_session_token     optional\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.credentials",
   :wiki-url
   "/cognitect.aws.credentials-api.html#cognitect.aws.credentials/profile-credentials-provider"}
  {:raw-source-url nil,
   :name "stop",
   :file "src/cognitect/aws/credentials.clj",
   :source-url nil,
   :line 112,
   :var-type "function",
   :arglists ([credentials]),
   :doc
   "Stop auto-refreshing the credentials.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.credentials",
   :wiki-url
   "/cognitect.aws.credentials-api.html#cognitect.aws.credentials/stop"}
  {:raw-source-url nil,
   :name "system-property-credentials-provider",
   :file "src/cognitect/aws/credentials.clj",
   :source-url nil,
   :line 187,
   :var-type "function",
   :arglists ([]),
   :doc
   "Return the credentials from the system properties.\n\nLook at the following properties:\n* aws.accessKeyId  required\n* aws.secretKey    required\n\nReturns nil if any of the required properties is blank.\n\nLogs error if one of the required properties is blank but\nthe other is not.\n\nAlpha. Subject to change. ",
   :namespace "cognitect.aws.credentials",
   :wiki-url
   "/cognitect.aws.credentials-api.html#cognitect.aws.credentials/system-property-credentials-provider"}
  {:raw-source-url nil,
   :name "chain-region-provider",
   :file "src/cognitect/aws/region.clj",
   :source-url nil,
   :line 28,
   :var-type "function",
   :arglists ([providers]),
   :doc
   "Chain together multiple region providers.\n\n`fetch` calls each provider in order until one returns a non-nil result,\nor returns nil.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.region",
   :wiki-url
   "/cognitect.aws.region-api.html#cognitect.aws.region/chain-region-provider"}
  {:raw-source-url nil,
   :name "default-region-provider",
   :file "src/cognitect/aws/region.clj",
   :source-url nil,
   :line 100,
   :var-type "function",
   :arglists ([http-client]),
   :doc
   "Returns a chain-region-provider with, in order:\n\n  environment-region-provider\n  system-property-region-provider\n  profile-region-provider\n  instance-region-provider\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.region",
   :wiki-url
   "/cognitect.aws.region-api.html#cognitect.aws.region/default-region-provider"}
  {:raw-source-url nil,
   :name "environment-region-provider",
   :file "src/cognitect/aws/region.clj",
   :source-url nil,
   :line 42,
   :var-type "function",
   :arglists ([]),
   :doc
   "Returns the region from the AWS_REGION env var, or nil if not present.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.region",
   :wiki-url
   "/cognitect.aws.region-api.html#cognitect.aws.region/environment-region-provider"}
  {:raw-source-url nil,
   :name "fetch",
   :file nil,
   :source-url nil,
   :var-type "function",
   :arglists ([_]),
   :doc "Returns the region found by this provider, or nil.",
   :namespace "cognitect.aws.region",
   :wiki-url
   "/cognitect.aws.region-api.html#cognitect.aws.region/fetch"}
  {:raw-source-url nil,
   :name "fetch-async",
   :file "src/cognitect/aws/region.clj",
   :source-url nil,
   :line 116,
   :var-type "function",
   :arglists ([provider]),
   :doc
   "Returns a channel that will produce the result of calling fetch on\nthe provider.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.region",
   :wiki-url
   "/cognitect.aws.region-api.html#cognitect.aws.region/fetch-async"}
  {:raw-source-url nil,
   :name "instance-region-provider",
   :file "src/cognitect/aws/region.clj",
   :source-url nil,
   :line 88,
   :var-type "function",
   :arglists ([http-client]),
   :doc
   "Returns the region from the ec2 instance's metadata service,\nor nil if the service can not be found.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.region",
   :wiki-url
   "/cognitect.aws.region-api.html#cognitect.aws.region/instance-region-provider"}
  {:raw-source-url nil,
   :name "profile-region-provider",
   :file "src/cognitect/aws/region.clj",
   :source-url nil,
   :line 58,
   :var-type "function",
   :arglists ([] [profile-name] [profile-name f]),
   :doc
   "Returns the region from an AWS configuration profile.\n\nArguments:\n\n  f             File    The profile configuration file. (default: ~/.aws/config)\n  profile-name  string  The name of the profile in the file. (default: default)\n\nParsed properties:\n\n  region        required\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.region",
   :wiki-url
   "/cognitect.aws.region-api.html#cognitect.aws.region/profile-region-provider"}
  {:raw-source-url nil,
   :name "system-property-region-provider",
   :file "src/cognitect/aws/region.clj",
   :source-url nil,
   :line 50,
   :var-type "function",
   :arglists ([]),
   :doc
   "Returns the region from the aws.region system property, or nil if not present.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.region",
   :wiki-url
   "/cognitect.aws.region-api.html#cognitect.aws.region/system-property-region-provider"}
  {:raw-source-url nil,
   :name "capped-exponential-backoff",
   :file "src/cognitect/aws/retry.clj",
   :source-url nil,
   :line 28,
   :var-type "function",
   :arglists ([base max-backoff max-retries]),
   :doc
   "Returns a function of the num-retries (so far), which returns the\nlesser of max-backoff and an exponentially increasing multiple of\nbase, or nil when (>= num-retries max-retries).\nSee with-retry to see how it is used.\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.retry",
   :wiki-url
   "/cognitect.aws.retry-api.html#cognitect.aws.retry/capped-exponential-backoff"}
  {:raw-source-url nil,
   :name "default-backoff",
   :file "src/cognitect/aws/retry.clj",
   :source-url nil,
   :line 41,
   :var-type "var",
   :doc
   "Returns (capped-exponential-backoff 100 20000 3).\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.retry",
   :wiki-url
   "/cognitect.aws.retry-api.html#cognitect.aws.retry/default-backoff"}
  {:raw-source-url nil,
   :name "default-retriable?",
   :file "src/cognitect/aws/retry.clj",
   :source-url nil,
   :line 47,
   :var-type "var",
   :doc
   "A fn of http-response which returns true if http-response contains\na cognitect.anomalies/category of :cognitect.anomalies/busy or\n:cognitect.anomalies/unavailable\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.retry",
   :wiki-url
   "/cognitect.aws.retry-api.html#cognitect.aws.retry/default-retriable?"}
  {:raw-source-url nil,
   :name "invoke",
   :file "src/cognitect/aws/client/api/async.clj",
   :source-url nil,
   :line 51,
   :var-type "function",
   :arglists ([client op-map]),
   :doc
   "Async version of cognitect.aws.client.api/invoke. Returns\na core.async channel which delivers the result.\n\nAdditional supported keys in op-map:\n\n:ch - optional, channel to deliver the result\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.client.api.async",
   :wiki-url
   "/cognitect.aws.client.api-api.html#cognitect.aws.client.api.async/invoke"})}
