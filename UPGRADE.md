# Upgrade Notes

## unreleased

### HTTP client

This release provides a new `java.net.http`-based HTTP client, without 
external dependencies. 

If you need to run on java 8 (that doesn't include `java.net.http`), you
must add an explicit dependency on `com.cognitect/http-client` in your
project, to make the previous HTTP client implementation available again
(it will be automatically used when running on java 8, as long as it is
in the classpath). This is not a dependency in aws-api anymore, so we
don't force it to all users.

If you only need to run on java 11+, the new HTTP client is now the default,
and the change should be transparent. If that is not the case, please open
an issue. In case you need to revert to the previous HTTP client implementation,
you must add an explicit dependency on `com.cognitect/http-client` in your
project, and provide `:http-client` in the config map when creating your client:

```clojure
(def http-client (cognitect.aws.http.cognitect/create))

(def s3 (aws/client {:api :s3
                     :http-client http-client}))
```

Note that using the old HTTP client in java 11+ is not recommended, so only
do so if you find a problem with the new HTTP client (and remember to open
an issue).

## 0.8.430

This release changed the behavior of the following functions:

### com.cognitect.aws.api/client

As of 0.8.430, each aws-api client uses a single shared http-client by
default.  Before this release, each aws-client got its own instance of
http-client by default, which caused the number of threads consumed to
increase linearly in relation to the number of aws-clients created.
To reduce resource consumption in the case of many aws-clients, we
recommended that you create a single instance of the http-client and
explicitly share it across all aws-clients. This is no longer
necessary.

### com.cognitect.aws.api/stop

With the introduction of a shared http-client, this function was
updated so that it has no effect when using the shared http-client,
but will continue to call `cognitect.aws.http/stop` on any other
http-client instance.

### effects

These changes have the following effects:

Programs that were creating multiple aws-clients without supplying
an http-client, and without ever calling stop, will see a reduction
in resource consumption.

Programs that were creating an instance of
`cognitect.aws.client.api/default-http-client` and sharing it across
aws-clients should see no change. You can, however, safely stop doing
that.

For programs that were using the default aws-client constructor and
calling stop on each aws-client, the shared http-client will not be
shut down. This should have no negative impact on resource consumption,
as there is only one http-client in this case, and its resources are
managed by aws-api.

For programs that were creating multiple aws-clients in order to get
around an ["Ops limit reached"
error](https://github.com/cognitect-labs/aws-api/issues/98), this is a
breaking change. For this case, we recommend, for now, that you supply
a new http-client for each aws-client.
