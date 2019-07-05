{:namespaces
 ({:doc
   "API functions for using a client to interact with AWS services.",
   :name "cognitect.aws.client.api",
   :wiki-url "cognitect.aws.client.api-api.html",
   :source-url nil}
  {:doc
   "Contains credentials providers and helpers for discovering credentials.\n\nAlpha. Subject to change.",
   :name "cognitect.aws.credentials",
   :wiki-url "cognitect.aws.credentials-api.html",
   :source-url nil}
  {:doc nil,
   :name "cognitect.aws.http.cognitect",
   :wiki-url "cognitect.aws.http.cognitect-api.html",
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
   :name "capped-exponential-backoff",
   :file "src/cognitect/aws/retry.clj",
   :source-url nil,
   :line 26,
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
   :line 39,
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
   :line 45,
   :var-type "var",
   :doc
   "A fn of http-response which returns true if http-response contains\na cognitect.anomalies/category of :cognitect.anomalies/busy or\n:cognitect.anomalies/unavailable\n\nAlpha. Subject to change.",
   :namespace "cognitect.aws.retry",
   :wiki-url
   "/cognitect.aws.retry-api.html#cognitect.aws.retry/default-retriable?"})}
