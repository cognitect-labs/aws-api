# Development

See [Contributing](../README.md#Contributing) for contribution guidelines.

## Setup

### aws-api

```
git clone git@github.com:cognitect-labs/aws-api.git
cd aws-api
clj -Sdeps
```


## Run tests

We need the dependencies in the `:dev` alias and the test runner in the `:test` or `:test-integration`
aliases in order to run the tests.

To run the integration tests, you'll need to bind the AWS_PROFILE env var to a profile named
`aws-api-test`, which should be configured to access a development account in order to avoid running
destructive operations in a prod account.

```shell
;; unit tests
clj -M:dev:test

;; integration tests
AWS_PROFILE=aws-api-test clj -M:dev:test-integration
```

### Add new tests

Tests live in `test/src`. Unit tests should be in a parallel path to the namespace you want to test,
adding `_test` in the end of the filename. For example,
`test/src/cognitect/aws/credentials_test.clj` contains tests for `src/cognitect/aws/credentials.clj`

Integration tests go in `test/src/cognitect/aws/integration/`. When creating integration tests,
please add `^:integration` in `deftest`. Example:

```clj
(deftest ^:integration do-something-with-a-real-aws-service
  ...)
```

It is also important to ensure that the `AWS_PROFILE` env var is the test profile `aws-api-test`. To
do this add this at the beginning of your integration test namespace:

```clj
(ns cognitect.aws.integration.s3-test
  (:require [clojure.test :refer [use-fixtures]]
            [cognitect.aws.integration.fixtures :as fixtures]))

(use-fixtures :once fixtures/ensure-test-profile)
```

## Build

### Build and install in your local ~/.m2 (or wherever you store maven repos)

```shell
build/package
```

## CI/CD

This repo relies on GitHub Actions for CI/CD. See the `.github/workflows/` directory.

### Secrets

The integration tests require an AWS access key and secret key pair to run. The `Run integration
tests` step in the `test.yml` file has an `env` which refers to these two secrets, whose values are
stored as GitHub repository secrets.


## Release

The release is automated via a GitHub workflow (`.github/workflows/release.yml`). The workflow can
be triggered on demand by an authorized user.

The release workflow makes use of the `build/release` script.

```shell
build/release
```

In summary, the release process:

* Releases are done from the `main` branch of the repository.
* The release artifacts are built, signed and deployed to Maven central.
* The git repository is tagged with a release tag.
* Updates `CHANGES.md`, `README.md`, `latest-releases.edn`
* Updates the API documentation in the `gh-pages` branch

### pre-release checklist

* Ensure the `CHANGES.md` file (and, optionally, the `UPGRADE.md` file) contains the expected latest
  unreleased changes, under a heading of `## DEV` near the top of the file just below `# aws-api`
  header. During the release, the version updater will replace the `DEV` with the proper release
  number and release date.
* (Optional) If releasing a beta release, create a file named `VERSION_SUFFIX` at the root of the
  project, containing a beta release suffix such as `-beta01` (with hyphen).

### post-release checklist

* When successful, the released artifacts will appear in [Maven
  Central](https://repo.maven.apache.org/maven2/com/cognitect/aws/api/) possibly after a few minutes
  delay.
* Make release announcements.
* (Optional) close any open issues that are fixed by the release.


### release announcements

An aws-api release should be announced in the following places:

* [Clojure google group](https://groups.google.com/g/clojure)
* Clojurians Slack, `#releases`, `#announcements`, and `#aws` channels
  * Post first in `#releases`
  * Cross-post to the other two channels (linking to the `#releases` post)

To craft the release verbiage, look at previous announcements, or use the following example. Include
hyperlinks of the issue numbers to the GitHub issues.

Subject: `[ANN] Nubank's aws-api 0.8.666`

Body:

```
Nubank's aws-api 0.8.666 is now available!

0.8.666 / 2023-04-27
  * 301 gets cognitect.anomalies/incorrect instead of cognitect.anomalies/fault #237
0.8.664 / 2023-04-26
  * Safely return byte arrays from ByteBuffers, honoring the position and remaining attributes, copying the underlying byte array when necessary. #238
  * Upgrade to com.cognitect/http-client "1.0.123", which includes status and headers in anomalies.
    * Fixes #171
    * Improves #15

Obs: the anomaly fix provides users the opportunity to detect 301s and programmatically recover when the "x-amz-bucket-region" header is present.

README: https://github.com/cognitect-labs/aws-api/
API: https://cognitect-labs.github.io/aws-api/
Changelog: https://github.com/cognitect-labs/aws-api/blob/master/CHANGES.md
Upgrade Notes: https://github.com/cognitect-labs/aws-api/blob/master/UPGRADE.md
Latest Releases of api, endpoints, and all services: https://github.com/cognitect-labs/aws-api/blob/master/latest-releases.edn
```

Note: there should be a public GitHub issue for any significant fix or change. Create a public issue
if necessary.

#### implementation details

A few words about some lower-level implementation details which the release script makes use of.

* The `build/revision` script calculates the release version (see `doc/versioning.md` for more
  details).
* The `version_updater.clj` tool is responsible for updating four files with the release version:
  `README.md`, `CHANGES.md`, `UPGRADE.md`, and `latest-releases.edn`. It makes use of the
  `build/revision` script. Note the `deps.edn` alias `update-versions` which the release script uses
  to invoke this tool.


## REPL

There are various options for opening a REPL.

### cider-nrepl

1. `M-x cider-jack-in`

CIDER jack-in is a convenient way to start up an nrepl without having to manually configure the
nrepl and cider-nrepl dependencies. However, if you wish to use additional deps.edn aliases (e.g.
`dev`, `examples`), you will have to tweak CIDER's default jack-in command. There are a couple ways
to do this:

  * Set `cider-clojure-cli-global-options` to e.g. "-M:dev" (global setting for all projects).
  * Set `cider-clojure-cli-aliases` to e.g. ":dev:examples"
  * Set either of the two aforementioned settings locally via `.dir-locals.el`, e.g.

  ``` emacs-lisp
  ((nil . ((cider-clojure-cli-aliases . ":dev:examples"))))
  ```
  * Prefix (`C-u`) the `M-x cider-jack-in` command, which allows you to edit the default jack-in
    command before it runs.

2. `M-x cider-connect`

  * Manually start an nrepl via CLI, then connect to it using CIDER. One option is to define a
`cider/nrepl` alias. Add to e.g. `~/.clojure/deps.edn`:

``` clojure
 :aliases {:cider/nrepl {:extra-deps {nrepl/nrepl {:mvn/version "x.y.z"}
                                      cider/cider-nrepl {:mvn/version "a.b.c"}}
                         :main-opts ["-m" "nrepl.cmdline" "--middleware" "[cider.nrepl/cider-middleware]"]}}
```

  * From the project root, start nrepl

``` shell
clj -M:dev:cider/nrepl
```

  * Connect to nrepl from CIDER (`C-c M-c`) using indicated host and port.

### IntelliJ IDEA / Cursive

1. Configure a REPL Run Configuration ("Run"->"Edit Configurations").
1. Click plus "+" to "Add new Configuration".
1. Select "Clojure REPL, Local".
1. In the configuration window
  * Give it a display name
  * Select "nREPL" for type of REPL to run
  * Select "Run with Deps" for "How to run it". Add any additional desired aliases as a
  comma-delimited string, e.g. `dev:examples`.

Now the configured REPL can be launched (run or debug) via "Run" top-level menu or Run
Configurations dropdown.


Implementation Notes
====================

## http client

This library makes uses of an underlying http client object to aid in http request/response.
Originally that client has been the `com.cognitect/http-client` library. More recently, we've
developed a newer client based on the `java.net.http` package which is present in JDK 11 and up.

What follows is notes on http clients.

### timeouts

In the context of http clients and connections, "timeout" may refer to one of (at least) three values:

* name resolution timeout aka resolve timeout
* connect timeout
* read response timeout aka idle timeout

For the cognitect client (which is based on an underlying Jetty http client):

* resolve timeout defaults to 5 seconds
* connect timeout defaults to 5 seconds
* idle timeout is unspecified, which means unbounded

For the java.net.http client, there is no distinct "resolve timeout", therefore we have set the
"connect timeout" to be 10 seconds, encompassing the sum of the previous client's resolve and
connect timeout defaults.

* resolve timeout (not applicable)
* connect timeout defaults to 10 seconds
* idle timeout is unspecified, which means unbounded

### restricted headers

Potentially confusing because "restricted headers" may be referring to one of two separate topics:

* The headers which are disallowed by the java.net.HttpClient implementation, because the
  implementation will provide its own values for these headers
  ([doc](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/module-summary.html)).
* The headers which are forbidden by the Fetch API spec which runs in browsers
  ([doc](https://developer.mozilla.org/en-US/docs/Glossary/Forbidden_header_name))

aws-api is concerned with only the first topic.
