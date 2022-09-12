# Test support

aws-api provides a [test double](http://xunitpatterns.com/Test%20Double.html)
implementation to test code that uses aws-api offline and even with no AWS
account. To use it, you must declare a handler fn or literal response for every
op that will be invoked (via `aws/invoke` or `aws/invoke-async`) during a
test, e.g.

```clojure
(require '[cognitect.aws.client.test-double :as test]
         '[cognitect.aws.client.api :as aws])

;; implementation being tested
(defn do-something [s3-client]
  (let [res (aws/invoke s3-client {:op :CreateBucket
                                   :request {:Bucket "a-bucket"}})]
    ;; do stuff with res
    ))

;; test using a handler function
(let [test-s3-client
      (test/client {:api :s3
                    :ops {:ListBuckets (fn [{:keys [op request] :as op-map}]
                                         ;; do stuff based on op-map
                                         ;; then, return a value
                                         {:Location "def"})}})]
  (let [res (do-something test-s3-client)]
    ;; make assertions about res
    ))

;; test using a literal response
(let [test-s3-client
      (test/client {:api :s3
                    :ops {:ListBuckets {:Location "a-location"}}})]
  (let [res (do-something test-s3-client)]
    ;; make assertions about res
    ))
```

### Test Doubles

[Test Double](http://xunitpatterns.com/Test%20Double.html) is a general term for an object
that doubles for a "real" object in a test. There are many types of test doubles and plenty
of discussion about the costs/benefits of each, however, aside from explicitly supporting
[test stubs](http://xunitpatterns.com/Test%20Stub.html), we are not supporting any other
type of test double directly. Handler functions, however, give you leverage to implement
any other type of test double.

### Features

- you can bind literal values or handler functions to the keys in the map bound to :ops
  - literal values will be returned as/is
  - handlers should be functions of the op-map passed to invoke and return a value valid
    for the op

### Helpful Feedback

`client` will throw when you declare an op that is not supported by the service

`invoke` will return an anomaly when
- you invoke an op that is not supported by the service
- you invoke an op that was not instrumented in the client constructor
- you invoke with an invalid :request payload

`invoke-async` will put an anomaly on the channel it returns, following
the same conditions as `invoke`

### Limitations

- the test client only supports the `invoke`, `invoke-async`, and `stop` functions supported
  by the normal aws api client
- as much as we'd love to, we have no reliable way to validate or generate responses that
  mimic those produced by AWS, therefore
  - you must declare a handler or response map for every op that will be invoked during a test
  - `client` will not validate the response payloads you provide