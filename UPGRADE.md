# Upgrade Notes

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
