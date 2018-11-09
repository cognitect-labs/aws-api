# Versioning

To use aws-api in your application, you depend on
`com.cognitect.aws/api`, `com.cognitect.aws/endpoints` and
`com.cognitect.aws/<service>`, e.g. `com.cognitect.aws/s3`. `api`, `endpoints`,
and `<service>`
all have different release schedules and version semantics.

## Version semantics

## com.cognitect.aws/api

    0.1.[rev]

`rev` is the revision in the git repository.

## com.cognitect.aws/endpoints

    [source-rev].[source.repo.tag]

`source-rev` indicates which aws-sdk (or other resource) we are
generating from, and `source.repo.tag` indicates the tag in the source
repository from which we are generating the endpoints library. For
example, `endpoints-1.1.11.444` is sourced from
[aws-sdk-java](https://github.com/aws/aws-sdk-java), per the table
below, at the `1.11.444` tag.

| source-rev | source repository | example source tag | example endpoints version |
|------------|-------------------|--------------------|---------------------------|
| 1          | aws-sdk-java      | 1.11.444           | 1.1.11.444                |

If we start to source endpoints from a different repository,
we'll add a row to this table.

### com.cognitect.aws/<service>

    [generator-rev].[source.repo.tag]

`generator-rev` indicates the revision of the code we use to
generate all the services and `source.repo.tag` is the tag in the aws
repository from which we are generating service libraries. For example,
`s3-631.2.346.0` is sourced from [aws-sdk-js](https://github.com/aws/aws-sdk-js),
per the table below, at the `2.347.0` tag.

| generator-rev | source repository | example source tag | example service version |
|---------------|-------------------|--------------------|-------------------------|
| >= 631        | aws-sdk-js        | v2.347.0           | 631.2.347.0             |

If we start to source services from a different repository,
we'll add a row to this table.

## Release schedules

### com.cognitect.aws/api

This is core engine, and we'll release it whenever we have a
meaningful addition to deliver.

All of the other libs are generated from other sources, so we follow
their release schedules.

### com.cognitect.aws/endpoints

This is generated from source data in an aws github repository, so we
periodically check to see if there are any new release tags in the
source repo. When there are, we diff the endpoint resource at the
first new tag with the last released tag. If there is a diff, we
cut a release at that new tag, and then do the same thing, comparing
the next new tag to the last released tag, until we run out of new
tags.

For example, if the last version we released is `1.1.11.444`, we look
to see if there are any tags newer than `1.11.444` in the source
repo. If we find e.g. `1.11.445`, `1.11.446`, and `1.11.447`, we'll
diff `1.11.445` against `1.11.444`. If the endpoints resource changed,
we'll cut a `1.1.11.445` release and then continue, comparing
`1.1.446` to `1.1.445`, and so on.

If there is no difference, we move to the next tag (`1.1.446`) and
compare it to our last-released basis (`1.1.444`), and so on.

### com.cognitect.aws/<service>

This works just like `endpoints`, above, except that we do the same
thing for every aws service available in the source repository.
