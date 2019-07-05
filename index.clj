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
  {:doc "Impl, don't call directly.",
   :name "cognitect.aws.http",
   :wiki-url "cognitect.aws.http-api.html",
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
   :name "-stop",
   :file nil,
   :source-url nil,
   :var-type "function",
   :arglists ([_]),
   :doc "Stops the client, releasing resources",
   :namespace "cognitect.aws.http",
   :wiki-url "/cognitect.aws.http-api.html#cognitect.aws.http/-stop"}
  {:raw-source-url nil,
   :name "-submit",
   :file nil,
   :source-url nil,
   :var-type "function",
   :arglists ([_ request channel]),
   :doc
   "Submit an http request, channel will be filled with response. Returns ch.\n\nRequest map:\n\n:scheme                 :http or :https\n:server-name            string\n:server-port            integer\n:uri                    string\n:query-string           string, optional\n:request-method         :get/:post/:put/:head/:delete\n:headers                map from downcased string to string\n:body                   ByteBuffer, optional\n:timeout-msec           opt, total request send/receive timeout\n:meta                   opt, data to be added to the response map\n\ncontent-type must be specified in the headers map\ncontent-length is derived from the ByteBuffer passed to body\n\nResponse map:\n\n:status            integer HTTP status code\n:body              ByteBuffer, optional\n:headers           map from downcased string to string\n:meta              opt, data from the request\n\nOn error, response map is per cognitect.anomalies.\n\nAlpha. This will absolutely change.",
   :namespace "cognitect.aws.http",
   :wiki-url "/cognitect.aws.http-api.html#cognitect.aws.http/-submit"}
  {:raw-source-url nil,
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
