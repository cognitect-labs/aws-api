# aws-api

aws-api is a Clojure library which provides programmatic access to AWS
services from your Clojure program.

* [API Docs](https://cognitect-labs.github.io/aws-api/)

## Rationale

AWS APIs are data-oriented in both the "send data, get data back"
sense, and the fact that all of the operations and data structures for
every service are, themselves, described in data which can be used to
generate mechanical transformations from application data to wire data
and back. This is exactly what we want from a Clojure API.

Using the AWS Java SDK directly via interop requires knowledge of
OO hierarchies of what are basically data classes, and while the
existing Clojure wrappers hide much of this from you, they don't
hide it from your process.

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

Each AWS SDK has its own copy of the data
descriptions in their github repos. We use
[aws-sdk-js](https://github.com/aws/aws-sdk-js/) as
the source for these, and release individual artifacts for each api.
The [api descriptors](https://github.com/aws/aws-sdk-js/tree/master/apis)
include the AWS `api-version` in their filenames (and in their data). For
example you'll see both of the following files listed:

    dynamodb-2011-12-05.normal.json
    dynamodb-2012-08-10.normal.json

Whenever we release com.cognitect.aws/dynamodb, we look for the
descriptor with the most recent API version. If aws-sdk-js-v2.351.0
contains an update to dynamodb-2012-08-10.normal.json, or a new
dynamodb descriptor with a more recent api-version, we'll make a
release whose version number includes the 2.351.0 from the version
of aws-sdk-js.

We also include the revision of our generator in the version. For example,
`com.cognitect.aws/dynamo-db-653.2.351.0` indicates revision `653` of the
generator, and tag `v2.351.0` of aws-sdk-js.

* See [Versioning](/doc/versioning.md) for more about how we version releases.
* See [latest releases](latest-releases.edn) for a list of the latest releases of
`api`, `endpoints`, and all supported services.

## Usage

### deps

To use aws-api in your application, you depend on
`com.cognitect.aws/api`, `com.cognitect.aws/endpoints` and the service(s)
of your choice, e.g. `com.cognitect.aws/s3`.

To use, for example, the s3 api, add the following to deps.edn

``` clojure
{:deps {com.cognitect.aws/api       {:mvn/version "0.8.352"}
        com.cognitect.aws/endpoints {:mvn/version "1.1.11.636"}
        com.cognitect.aws/s3        {:mvn/version "747.2.533.0"}}}
```

* See [latest releases](latest-releases.edn) for a list of the latest releases of
`api`, `endpoints`, and all supported services.

### explore!

Fire up a repl using that deps.edn, and then you can do things like this:

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
;; http-request and http-response are in the metadata
(meta *1)

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

## Responses, success, failure

Barring client side exceptions, every operation on every service will
return a map. If the operation is successful, the map will be in the
shape described by `(-> client aws/ops op :response)`.  If AWS
indicates failure with an HTTP status code >= 400, the map will
include a `:cognitect.anomalies/category` key, so you can check for
the absence/presence of that key to determine success/failure.

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

Most of the time you can create a client and it figures out the correct endpoint for you,
but there are exceptions. You may want to use a proxy server or connect to a local dynamodb,
or perhaps you've found a bug that you could work around if you could supply the correct
endpoint. All of this can be accomplished by supplying an `:endpoint-override` map
to the `client` constructor:

``` clojure
(def ddb (aws/client {:api :dynamodb
                      :endpoint-override {:protocol :http
                                          :hostname "localhost"
                                          :port     8000}}))
```

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

The aws-api client uses an http-client to send requests to AWS,
including any operations you invoke _and_ fetching the region and
credentials when you're running in EC2 or ECS. By default, each
aws-api client creates its own http-client, which, in turn, manages
its own resources. Invoke `cognitect.aws.client.api/stop` on the
client if you want it to shut down any resources it and its
http-client are using.

If you're creating multiple aws-api clients, you can, optionally,
create a single http-client and share it across aws-api clients e.g.

``` clojure
(require '[cognitect.aws.client.api :as aws])
(def http-client (aws/default-http-client))
(def s3-client (aws/client {:api :s3 :http-client http-client}))
(def ssm-client (aws/client {:api :ssm :http-client http-client}))
;; etc
```

If you call `stop` on `s3-client` or `ssm-client` in this example, the
single http-client gets shut down for both.

## Contributing

This library is open source, developed internally by Cognitect.
Issues can be filed using GitHub issues for this project. Because
aws-api is incorporated into products and client projects, we prefer
to do development internally and are not accepting pull requests or
patches.

## Troubleshooting

### General

#### nodename nor servname provided, or not known

This indicates that the configured endpoint is incorrect for the service/op
you are trying to perform.

Remedy: check [AWS Regions and Endpoints](https://docs.aws.amazon.com/general/latest/gr/rande.html)
the proper endpoint and use the `:endpoint-override` key when creating a client,
e.g.

``` clojure
(def s3-control {:api :s3control})
(aws/client {:api :s3control
             :endpoint-override {:hostname (str my-account-id ".s3-control.us-east-1.amazonaws.com")}})
```

#### No known endpoint.

This indicates that the data in the `com.cognitect.aws/endpoints` lib
(which is derived from
[endpoints.json](https://github.com/aws/aws-sdk-java/blob/master/aws-java-sdk-core/src/main/resources/com/amazonaws/partitions/endpoints.json))
does not support the `:api`/`:region` combination you are trying to
access.

Remedy: check [AWS Regions and Endpoints](https://docs.aws.amazon.com/general/latest/gr/rande.html),
and supply the correct endpoint as described in [nodename nor servname provided, or not known](#nodename-nor-servname-provided-or-not-known), above.

### S3 Issues

#### "Invalid 'Location' header: null"

This indicates that you are trying to access an S3 resource (bucket or object)
that resides in a different region from the client's region.

Remedy: create a new s3 client in the same region you are trying to access.

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
