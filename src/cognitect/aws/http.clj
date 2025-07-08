;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.http
  "Impl, don't call directly."
  (:require [clojure.edn :as edn]
            [clojure.core.async :as a]
            [clojure.string :as str]
            [cognitect.aws.resources :as resources]
            [cognitect.aws.dynaload :as dynaload]))

(set! *warn-on-reflection* true)

(defprotocol HttpClient
  (-submit [_ request channel]
    "Submit an http request, channel will be filled with response. Returns ch.

     Request map:

     :scheme                 :http or :https
     :server-name            string
     :server-port            integer
     :uri                    string
     :query-string           string, optional
     :request-method         :get/:post/:put/:head/:delete
     :headers                map from downcased string to string
     :body                   ByteBuffer, optional
     :timeout-msec           opt, total request send/receive timeout
     :meta                   opt, data to be added to the response map

     content-type must be specified in the headers map
     content-length is derived from the ByteBuffer passed to body

     Response map:

     :status            integer HTTP status code
     :body              ByteBuffer, optional
     :headers           map from downcased string to string
     :meta              opt, data from the request

     On error, response map is per cognitect.anomalies.

     Alpha. This will absolutely change.")
  (-stop [_] "Stops the client, releasing resources"))

(defn submit
  ([client request]
   (-submit client request (a/chan 1)))
  ([client request channel]
   (-submit client request channel)))

(defn stop
  "Stops the client, releasing resources.

  Alpha. Subject to change."
  [client]
  (-stop client))

(defn client?
  [c]
  (satisfies? HttpClient c))

(defn read-config
  [url]
  (-> url slurp edn/read-string))

;; TODO consider providing config arguments to http constructor
(defn- configured-client
  "If a single `cognitect_aws_http.edn` is found on the classpath,
  returns the symbol bound to `:constructor-var`.

  If none are found, use this library's default.

  Throws if more than one `cognitect_aws_http.edn` files are found."
  []
  (let [cfgs (resources/resources "cognitect_aws_http.edn")]
    (case (count cfgs)
      0 'cognitect.aws.http.default/create
      1 (-> cfgs first read-config :constructor-var)

      (throw (ex-info "Found more than one cognitect_aws_http.edn file in the classpath. There must be at most one." {:config cfgs})))))

(defn resolve-http-client
  [http-client-or-sym]
  (let [c (or (when (symbol? http-client-or-sym)
                (let [ctor @(dynaload/load-var http-client-or-sym)]
                  (ctor)))
              http-client-or-sym
              (let [ctor @(dynaload/load-var (configured-client))]
                (ctor)))]
    (when-not (client? c)
      (throw (ex-info "not an http client" {:provided http-client-or-sym
                                            :resolved c})))
    c))

(defn uri-authority
  "Returns the normalized URI authority (RFC 3986 section 3.2) for the given URI components,
   good for building the full request URI, signing the request, and computing the Host header,
   according to RFC 9112 section 3.2:

       A client MUST send a Host header field in all HTTP/1.1 request messages. If the target URI
       includes an authority component, then a client MUST send a field value for Host that is identical
       to that authority component, excluding any userinfo subcomponent and its \"@\" delimiter.

   `uri-scheme` and `uri-host` are required. `uri-port` may be nil in case the default port
   for the scheme is used.

   Normalization follows RFC 9110 section 4.2.3 (default port for the scheme is omitted;
   scheme and host are normalized to lowercase). The userinfo component of the URI is
   always omitted as per RFC 9110 section 4.2.4.

       authority = host [ \":\" port ]"
  [uri-scheme uri-host uri-port]
  (let [scheme (str/lower-case (name uri-scheme))
        host (str/lower-case uri-host)
        is-default-port (case scheme
                          "http" (= uri-port 80)
                          "https" (= uri-port 443)
                          false)
        should-include-port (and (some? uri-port)
                                 (not is-default-port))]
    (str host (when should-include-port (str ":" uri-port)))))
