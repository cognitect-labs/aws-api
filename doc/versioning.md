# Versioning

To use aws-api in your application, you depend on
`com.cognitect.aws/api`, `com.cognitect.aws/endpoints` and
`com.cognitect.aws/<service>`, e.g. `com.cognitect.aws/s3`.

## Version semantics

The artifacts fall into one of three categories:

* `com.cognitect.aws/api`
* `com.cognitect.aws/endpoints`
* each `<service>` artifact (of which there are hundreds)

Each of these three categories of artifacts are capable of being released and versioned independently.

## com.cognitect.aws/api

    0.8.[rev]

`rev` is the revision in the git repository.

## endpoints and services

    [major-rev].[source.repo.tag]

Starting with release `871.2.29.35`, `endpoints` and service artifact versions are being prefixed
with the same major version prefix, currently `871`. The `2.29.35` is a tag in the AWS SDK source
repository, currently [AWS SDK Java v2](https://github.com/aws/aws-sdk-java-v2), from which both
`endpoints` and service artifacts are generated.

These artifacts are released independently and sporadically, only as needed - a given artifact will
only see a new release if the current tag of the AWS SDK source repository contains changes for that
artifact, or if a new service descriptor is discovered which has not yet been released.

Prior to release `871.2.29.35`, `endpoints` and services had different major version prefixes. This
is because they were generated from different, older AWS SDK repositories: [aws-sdk-java
v1](https://github.com/aws/aws-sdk-java) and [aws-sdk-js](https://github.com/aws/aws-sdk-js),
respectively. With the deprecation of both of those repositories, it was necessary to migrate to the
current Java v2 repository and to release new versions of all artifacts, which was release
`871.2.29.35`.

The following two sections provides more detail and historical context about `endpoints` and service
artifacts, respectively.

### com.cognitect.aws/endpoints

    [source-rev].[source.repo.tag]

`source-rev` indicates which aws-sdk (or other resource) we are
generating from, and `source.repo.tag` indicates the tag in the source
repository from which we are generating the endpoints library. For
example, `endpoints-1.1.11.444` is sourced from
[aws-sdk-java](https://github.com/aws/aws-sdk-java), per the table
below, at the `1.11.444` tag.

| source-rev | source repository | example source tag | example endpoints version |
|------------|-------------------|--------------------|---------------------------|
| >= 871     | aws-sdk-java-v2   | 2.29.35            | 871.2.29.35               |
| 1          | aws-sdk-java      | 1.11.444           | 1.1.11.444                |

If we start to source endpoints from a different repository,
we'll add a row to this table.

### com.cognitect.aws/&lt;service>

    [generator-rev].[source.repo.tag]

`source-rev` indicates which aws-sdk (or other resource) we are
generating from and `source.repo.tag` is the tag in the aws
repository from which we are generating service libraries. For example,
`s3-631.2.347.0` is sourced from [aws-sdk-js](https://github.com/aws/aws-sdk-js),
per the table below, at the `2.347.0` tag.

| source-rev | source repository | example source tag | example service version |
|------------|-------------------|--------------------|-------------------------|
| >= 871     | aws-sdk-java-v2   | 2.29.35            | 871.2.29.35             |
| >= 631     | aws-sdk-js        | v2.347.0           | 631.2.347.0             |

If we start to source services from a different repository,
we'll add a row to this table.

Prior to source revision `871`, the source revision was linked to the version of the code used to
generate the service artifacts. Beginning with revision `871` that is no longer true.

## Release schedules

### com.cognitect.aws/api

This is core engine, and we'll release it whenever we have a
meaningful addition to deliver.

All of the other libs are generated from other sources, so we follow
their release schedules.

### com.cognitect.aws/endpoints

This is generated from source data in an aws github repository, so we
periodically check to see if there are any changes to the endpoints resource at
the repository's latest tag, since the last released tag. If there is a diff, we
cut a release at that new tag.

For example, if the last version we released is `871.1.11.444`, and the
repository's most recent tag is `1.11.678`, we'll diff `1.11.445` against
`1.11.678`. If the endpoints resource changed, we'll cut a `871.1.11.678`
release.

### com.cognitect.aws/&lt;service>

This works just like `endpoints`, above, except that we do the same
thing for every aws service available in the source repository.


## Service API Versions

The api descriptors include the AWS `api-version` in their `:metadata`. If, for
any given service, the AWS SDK repository contains descriptors for more than one
api version, only the most recent api version will have an artifact generated
and released.

As of this writing, the [AWS SDK Java
v2](https://github.com/aws/aws-sdk-java-v2) repository has no such examples of
this. But if and when it does, this policy will continue to hold true.

For example: historically, services were generated from the
[aws-sdk-js](https://github.com/aws/aws-sdk-js) repository, which contains at
least one instance of this situation. In that repository you'll find both of
the following files:

    dynamodb-2011-12-05.normal.json
    dynamodb-2012-08-10.normal.json

In that case, whenever we released `com.cognitect.aws/dynamodb`, we look for the
descriptor with the most recent API version. If `aws-sdk-js-v2.351.0` contains
an update to `dynamodb-2012-08-10.normal.json`, or a new dynamodb descriptor
with a more recent api-version, we'd make a release whose version number
includes the `2.351.0` from the version of aws-sdk-js.
