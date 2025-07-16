# aws-api

aws-api provides programmatic access to AWS services from Clojure programs.

## Docs

* [API](https://cognitect-labs.github.io/aws-api/)
* [Data types for :request maps](https://github.com/cognitect-labs/aws-api/blob/main/doc/types.md)

## Rationale

AWS APIs are data-oriented in both the "send data, get data back"
sense, and the fact that all of the operations and data structures for
every service are, themselves, described in data which can be used to
generate mechanical transformations from application data to wire data
and back. This is exactly what we want from a Clojure API.

Using the AWS Java SDK directly via interop requires knowledge of
OO hierarchies of data classes, and while the existing Clojure wrappers
hide much of this from you, they don't hide it from your process.

aws-api is an idiomatic, data-oriented Clojure library for
invoking AWS APIs.  While the library offers some helper and
documentation functions you'll use at development time, the only
functions you ever need at runtime are `client`, which creates a
client for a given service and `invoke`, which invokes an operation on
the service. `invoke` takes a map and returns a map, and works the
same way for every operation on every service.

## Approach

AWS APIs are described in data which specifies operations, inputs, and
outputs. aws-api uses the same data descriptions to expose a
data-oriented interface, using service descriptions, documentation,
and specs which are generated from the source descriptions.

Most AWS SDKs have their own copies of these data descriptions in their github
repos. We use [aws-sdk-java-2](https://github.com/aws/aws-sdk-java-v2) as the
source for these, and release separate artifacts for each api.

* See [Versioning](/doc/versioning.md) for more info about how we version releases.
* See [latest releases](latest-releases.edn) for a list of the latest releases of
`api`, `endpoints`, and all supported services.

## Usage

### dependencies

To use aws-api in your application, you depend on
`com.cognitect.aws/api`, `com.cognitect.aws/endpoints` and the service(s)
of your choice, e.g. `com.cognitect.aws/s3`.

To use the s3 api, for example, add the following to deps.edn:

``` clojure
{:deps {com.cognitect.aws/api       {:mvn/version "0.8.741"}
        com.cognitect.aws/endpoints {:mvn/version "871.2.32.2"}
        com.cognitect.aws/s3        {:mvn/version "871.2.32.2"}}}
```

* See [latest releases](latest-releases.edn) for a listing of the latest releases of
`api`, `endpoints`, and all supported services.

### explore!

Fire up a REPL using that deps.edn, and then you can do things like this:

``` clojure
(require '[cognitect.aws.client.api :as aws])
```

Create a client:

```clojure
(def s3 (aws/client {:api :s3}))
```

Ask what ops your client can perform:

``` clojure
(aws/ops s3)
```

Look up docs for an operation:

``` clojure
(aws/doc s3 :CreateBucket)
```

Tell the client to let you know when you get the args wrong:

``` clojure
(aws/validate-requests s3 true)
```

Do stuff:

``` clojure
(aws/invoke s3 {:op :ListBuckets})
;; => {:Buckets [{:Name <name> :CreationDate <date> ,,,}]}

;; http-request and http-response are in the metadata
(meta *1)
;; => {:http-request {:request-method :get,
;;                    :scheme :https,
;;                    :server-port 443,
;;                    :uri "/",
;;                    :headers {,,,},
;;                    :server-name "s3.amazonaws.com",
;;                    :body nil},
;;     :http-response {:status 200,
;;                     :headers {,,,},
;;                     :body <input-stream>}
clj꞉user꞉> 

;; create a bucket in the same region as the client
(aws/invoke s3 {:op :CreateBucket :request {:Bucket "my-unique-bucket-name"}})

;; create a bucket in a region other than us-east-1
(aws/invoke s3 {:op :CreateBucket :request {:Bucket "my-unique-bucket-name-in-us-west-1"
                                            :CreateBucketConfiguration
                                            {:LocationConstraint "us-west-1"}}})

;; NOTE: be sure to create a client with region "us-west-1" when accessing that
;; bucket.

(aws/invoke s3 {:op :ListBuckets})
```

See the [examples](examples) directory for more examples.

## Responses, successes, redirects, and failures

Barring client side exceptions, every operation on every AWS service returns a map. If the operation is successful, the map is in the shape described by `(-> client aws/ops op :response)`. AWS documents
all HTTP status codes >= 300 as errors (see https://docs.aws.amazon.com/AmazonS3/latest/API/ErrorResponses.html), so when AWS returns an HTTP status code >= 300, aws-api returns an [anomaly map](https://github.com/cognitect-labs/anomalies), identified by a `:cognitect.anomalies/category` key, with the HTTP status bound to a `:cognitect.aws.http/status` key. When AWS provides an error response in the HTTP response body, aws-api coerces it to clojure data, and merges that into the anomaly map. Additionally, when AWS provides an error code, aws-api will bind it to a `:cognitect.aws.error/code` key. Example:

```clojure
{:Error                                                        ;; provided by AWS
 {:Message "The specified key does not exist."                 ;; provided by AWS
  :Code "NoSuchKey"}                                           ;; provided by AWS
 :cognitect.anomalies/category :cognitect.anomalies/not-found
 :cognitect.aws.http/status 404
 :cognitect.aws.error/code "NoSuchKey"}                        ;; derived from :Code, above
```

If you need more information when you receive an anomaly map, you can check `(-> response meta :http-response)` for the raw http response, including the `:status` and `:headers`.

### S3 GetObject and Conditional Requests

S3 [GetObject](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html) supports Conditional Requests with `:IfMatch`, `:IfNoneMatch`, `:IfModifiedSince`, and `:IfUnmodifiedSince`, which may result in 304s (for `:IfNoneMatch` and `:IfModifiedSince`) or 412s (for `:IfMatch`, and `:IfUnmodifiedSince`). AWS [documents all of these as errors](https://docs.aws.amazon.com/AmazonS3/latest/API/ErrorResponses.html#ErrorCodeList), and AWS SDKs throw exceptions for 412s *and* 304s. aws-api returns anomalies instead of throwing exceptions, so aws-api will return anomalies for both 412s and 304s.

### Error responses with status 200

Per https://aws.amazon.com/premiumsupport/knowledge-center/s3-resolve-200-internalerror/, AWS may return a 200 with an error response in the body,
in which case you should look for an error code in the body.

## Credentials

The aws-api client implicitly looks up credentials the same way the
[java SDK](https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html)
does.

To provide credentials explicitly, you pass an implementation
of `cognitect.aws.credentials/CredentialsProvider` to the
client constructor fn, .e.g

``` clojure
(require '[cognitect.aws.client.api :as aws])
(def kms (aws/client {:api :kms :credentials-provider my-custom-provider}))
```

If you're supplying a known access-key/secret pair, you can use
the `basic-credentials-provider` helper fn:

``` clojure
(require '[cognitect.aws.client.api :as aws]
         '[cognitect.aws.credentials :as credentials])

(def kms (aws/client {:api                  :kms
                      :credentials-provider (credentials/basic-credentials-provider
                                             {:access-key-id     "ABC"
                                              :secret-access-key "XYZ"})}))
```

See the [assume role example](./examples/assume_role_example.clj) for a more
involved example using AWS STS.

## Region lookup

The aws-api client looks up the region the same way the [java
SDK](https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/java-dg-region-selection.html)
does, with an additional check for a System property named
"aws.region" after it checks for the AWS_REGION environment variable
and before it checks your aws configuration.

## Endpoint Override

Most of the time you can create a client and it figures out the correct endpoint for you. The
endpoints of most AWS API operations adhere to the pattern documented in [AWS Regions and
Endpoints](https://docs.aws.amazon.com/general/latest/gr/rande.html). But there are exceptions.

* Some AWS APIs have operations which require custom endpoints (e.g. Kinesis Video [GetMedia](https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/API_dataplane_GetMedia.html)).
* You may want to use a proxy server or connect to a local dynamodb.
* Perhaps you've found a bug that you could work around if you could supply the correct endpoint.

All of this can be accomplished by supplying an `:endpoint-override` map to the `client`
constructor:

``` clojure
(def ddb (aws/client {:api :dynamodb
                      :endpoint-override {:protocol :http
                                          :hostname "localhost"
                                          :port     8000}}))
```

## Testing

aws-api provides a test-double client you can use to simulate `aws/invoke` in your tests.

See https://github.com/cognitect-labs/aws-api/blob/main/doc/testing.md.

### PostToConnection

The `:PostToConnection` operation on the `apigatewaymanagementapi`
client requires that you specify the API endpoint as follows:

``` clojure
(def client (aws/client {:api :apigatewaymanagementapi
                         :endpoint-override {:hostname "{hostname}"
                                             :path "/{stage}/@connections/"}}))
```

Replace `{hostname}` and `{stage}` with the hostname and the stage of
the connection to which you're posting (see
[https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-how-to-call-websocket-api-connections.html](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-how-to-call-websocket-api-connections.html)).

The client will append the `:ConnectionId` in the `:request` map to
the `:path` in the `:endpoint-override` map.

## http-client

An http client is used in the following contexts:

* The aws-api client uses an http client to carry out the http communication with an AWS endpoint
  when you invoke an operation.
* When running in EC2 or ECS, an http client may be used by the credentials provider to fetch
  credentials information from within the running instance.
* When running in EC2 or ECS, an http client may be used by the region provider to fetch region
  information from within the running instance.

### Shared http-client

By default, each aws-api client uses a single, shared http-client, whose resources are managed by
aws-api. See the `cognitect.aws.client.shared` namespace.

The `cognitect.aws.client.shared` namespace also defines a single, globally shared credentials
provider as well as a single, globally shared region provider - both of these also use the shared
http client.

### Default http-client

An http-client will be created from the `default-http-client` function of the
`cognitect.aws.client.api` namespace - this function returns a new instance of the default type of
http client.

The globally shared http-client will be an instance of this default type of client.

The http client returned by the `default-http-client` function will be one of the following:

* If the version of Java is recent enough (Java 11 or newer), the returned http client is a Java
  native implementation based on the
  [java.net.http](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpClient.html)
  module.
* If the Java version is older, the legacy http client based on `com.cognitect/http-client` will be
  used. However, this requires that you provide the `com.cognitect/http-client` dependency.
* It is possible to configure a custom type of default http client (see next section).
* If none of these conditions are met, an exception will be thrown.

NOTE: the behavior of `com.cognitect.aws.api/client` and `com.cognitect.aws.api/stop`
changed as of release 0.8.430. See [Upgrade
Notes](https://github.com/cognitect-labs/aws-api/blob/master/UPGRADE.md)
for more information.

### Overriding the http-client

There are two ways of overriding the default http client behavior:

* Setting the http client per each individual AWS client.
* Changing the default type of http client (and, consequently, the globally shared http-client instance).

#### Customize the http-client of an AWS client

Http clients can be set on each individual AWS client. When you create an AWS client, you can set
the http-client via the `:http-client` key.

Example:
```clj
(require '[cognitect.aws.http.java :as http-java-client])

(aws/client {:api :s3
             :http-client http-java-client/create})
```

The value of `:http-client` can be either:

* An http client instance.
* A fully qualified symbol which resolves to a function of zero arguments which, when invoked,
returns an http client instance.

#### Override the default shared http client

If you want to use a certain http-client by default in all clients, you can override the type of
http client that is created by default, including for the globally shared http client instance.

To do that, create a resource file called `cognitect_aws_http.edn` with content such as this:

```edn
{:constructor-var cognitect.aws.http.java/create}
```

The key must be the keyword `:constructor-var` and the value must be a fully qualified symbol which
resolves to a function of zero arguments which, when invoked, returns an http client.

This file must be available at runtime as a resource in the classpath of your application, and there
must be at most one such file.

## Troubleshooting

### Retriable errors

When the aws-api client encounters an error, it uses two functions
to determine whether to retry the request:

``` clojure
(retriable? [anomaly]
  ;; should return a truthy value when the anomaly* indicates that
  ;; the request is retriable.
  )

;; Then, if retriable? returns a truthy value:

(backoff [n-tries-so-far]
  ;; should return the number of milliseconds to wait before trying
  ;; again, or nil, which indicates that we have reached the max number
  ;; of retries and should not try again.
  )
```

*see [Cognitect anomalies](https://github.com/cognitect-labs/anomalies)

The defaults for these are:

``` clojure
cognitect.aws.retry/default-retriable?
cognitect.aws.retry/default-backoff
```

You can override these defaults by passing functions to
`cognitect.aws.client.api/client` bound to the keys `:retriable?` and
`:backoff`, e.g.

``` clojure
(cognitect.aws.client.api/client
  {:api        :iam
   :retriable? custom-retriable-fn
   :backoff    custom-backoff-fn})

```

#### default retriable?

The default retriable predicate,
`cognitect.aws.retry/default-retriable?`, returns a truthy value when
the value of `:cognitect.anomalies/category` is any of:

- `:cognitect.anomalies/busy`
- `:cognitect.anomalies/interrupted`
- `:cognitect.anomalies/unavailable`

Because we do not control the sources of these errors, we cannot
guarantee that every retriable error will be recognized. If you
encounter an error that you think should be retriable, you can supply
a custom predicate bound to the `:retriable?` key when you create a
client.

``` clojure
(cognitect.aws.client.api/client
  {:api        :iam
   :retriable? (fn [{:keys [cognitect.anomalies/category] :as error-info] ,,)})
```

Only `cognitect.anomalies/category` is controlled by aws-api, and you
should inspect the actual error to understand what other information
is available to you to decide whether or not a request is retriable.

#### default backoff

The default backoff, `cognitect.aws.retry/default-backoff`, is a
capped, exponential backoff, which returns `nil` after max-retries
have already been attempted.

If you wish to override this backoff strategy, you can supply a custom
function bound to the `:backoff` key when you create a client.

``` clojure
(cognitect.aws.client.api/client
  {:api     :iam
   :backoff (fn [n-tries-so-far] ,,)})
```

Don't forget to account for termination by returning nil after some number of retries.

You an also use `cognitect.aws.retry/capped-exponential-backoff` to
generate a function with different values for base, max-backoff, and
max-retries, and then pass that to `client`.

### nodename nor servname provided, or not known

This indicates that the configured endpoint is incorrect for the service/op
you are trying to perform.

Remedy: check [AWS Regions and Endpoints](https://docs.aws.amazon.com/general/latest/gr/rande.html) for
the proper endpoint and use the `:endpoint-override` key when creating a client,
e.g.

``` clojure
(def s3-control {:api :s3control})
(aws/client {:api :s3control
             :endpoint-override {:hostname (str my-account-id ".s3-control.us-east-1.amazonaws.com")}})
```

### UnknownOperationException

AWS will return an `UnknownOperationException` response when a client is configured with (or defaults to) an incorrect endpoint.

Remedy: check [AWS Regions and Endpoints](https://docs.aws.amazon.com/general/latest/gr/rande.html) for
the proper endpoint and use the `:endpoint-override` key when creating a client.

Note that some AWS APIs have custom endpoint requirements. For example, Kinesis Video [Get
Media](https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/API_dataplane_GetMedia.html)
operation requires a custom endpoint, e.g.

``` clojure
(def kvs (aws/client {:api :kinesisvideo ... }))

(def kvs-endpoint (:DataEndpoint (aws/invoke kvs {:op :GetDataEndpoint ... })))

(aws/client {:api :kinesis-video-media
             :region "us-east-1"
             :endpoint-override {:hostname (str/replace kvs-endpoint #"https:\/\/" "")}})
```

### No known endpoint.

This indicates that the data in the `com.cognitect.aws/endpoints` lib
(which is derived from
[endpoints.json](https://github.com/aws/aws-sdk-java-v2/blob/master/core/regions/src/main/resources/software/amazon/awssdk/regions/internal/region/endpoints.json))
does not support the `:api`/`:region` combination you are trying to
access.

Remedy: check [AWS Regions and Endpoints](https://docs.aws.amazon.com/general/latest/gr/rande.html),
and supply the correct endpoint as described in [nodename nor servname provided, or not known](#nodename-nor-servname-provided-or-not-known), above.

### Ops limit reached

The underlying http-client has a `:pending-ops-limit` configuration
which, when reached, results in an exception with the message "Ops
limit reached". As of this writing, aws-api does not provide access to
the http-client's configuration. Programs that encounter "Ops limit
reached" can avoid it by creating separate http-clients for each
aws-client. You may wish to explicitly stop
(`com.cognitect.aws.api/stop`) these aws-clients when the are not
longer in use to conserve resources.

### S3: "Invalid 'Location' header: null"

This indicates that you are trying to access a resource that resides in a
different region from that of the client.

As of v0.8.670, instead of this error, you'll see an AWS-provided payload decorated
with information about how to recover.

As of v0.8.662, the anomaly also includes status 301 and the "x-amz-bucket-region" header,
so you can now detect the 301 and create a new client in the region bound to the
"x-amz-bucket-region" header.

If you're using a version older than 0.8.662, you'll have to figure out the region
out of band (AWS console, etc).

## Contributing

aws-api is open source, developed internally at Nubank.
Issues can be filed using GitHub issues for this project. Because
aws-api is incorporated into products, we prefer to do development
internally and are not accepting pull requests or patches.

## Contributors

`aws-api` was extracted from an internal project at Cognitect, and
some contributors are missing from the commit log.  Here are all the
folks from Cognitect and Nubank who either committed code directly, or
contributed significantly to research and design:

[Timothy Baldridge](https://github.com/halgari)<br/>
[Scott Bale](https://github.com/scottbale)<br/>
[Marco Biscaro](https://github.com/marcobiscaro2112)<br/>
[David Chelimsky](https://github.com/dchelimsky)<br/>
[Maria Clara Crespo](https://github.com/mariaclaracrespo)<br/>
[Benoît Fleury](https://github.com/benfle)<br/>
[Fogus](https://github.com/fogus)<br/>
[Kyle Gann](https://github.com/kgann)</br>
[Stuart Halloway](https://github.com/stuarthalloway)<br/>
[Rich Hickey](https://github.com/richhickey)<br/>
[George Kierstein](https://github.com/MissInterpret)<br/>
[Carin Meier](https://github.com/gigasquid)<br/>
[Joe Lane](https://github.com/MageMasher)<br/>
[Alex Miller](https://github.com/puredanger)<br/>
[Michael Nygard](https://github.com/mtnygard)<br/>
[Ghadi Shayban](https://github.com/ghadishayban)<br/>
[Joseph Smith](https://github.com/solussd)<br/>
[Thayanne Sousa](https://github.com/thayannevls)<br/>
[Marshall Thompson](https://github.com/Glassonion)

## Copyright and License

Copyright © 2015 Cognitect

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
