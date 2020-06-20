(ns cognitect.aws.signing)

(defmulti sign-http-request
  "Sign the HTTP request."
  (fn [service endpoint credentials http-request]
    (get-in service [:metadata :signatureVersion])))

(defmulti presigned-url
  "Return a map with :presigned-url bound to a presigned URL for an HTTP request."
  (fn [context]
    (get-in context [:service :metadata :signatureVersion])))
