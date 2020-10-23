## Redesign

We're working on a new design for how aws-api processes requests, using
a new execution workflow that threads discreet steps together into a pipline.

This new design is still in development, but we'll be advertising alpha
releases in the form of git shas that you can use in your deps.

## What you get

### On the surface

- http response bodies are now proper streams
  - this means you can invoke `:GetObject` on an `:s3` client and pull down
    a file that is larger than memory
- you can now override things like `:region`, and even `:api` when you call `invoke`
- experimental diagnostics to inspect requests

``` clojure
(aws/invoke <client> <op-map>)

(cognitect.aws.diagnostics/summarize-log *1)
```


- presigned-url

``` clojure
(def get-object-url
  (:presigned-url (aws/invoke s3-client
                              {:op            :GetObject
                               :request       {:Bucket bucket :Key key}
                               :workflow      :cognitect.aws.alpha.workflow/presigned-url
                               :presigned-url {:expires 30}}))

;; Within the next 30 seconds, you can fetch the url using e.g. curl in a shell,
;; or this in your REPL:

(aws/invoke s3-client
            {:workflow      :cognitect.aws.alpha.workflow/fetch-presigned-url
             :presigned-url {:url get-object-url}})
```

### Under the hood

- everything runs in FJPool
- workflow model
  - possible modifications to workflow stack in the future, feedback requested
