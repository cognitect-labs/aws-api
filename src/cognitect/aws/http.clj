;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.http
  "Impl, don't call directly."
  (:require [clojure.edn :as edn]
            [clojure.core.async :as a]
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
  "If a single cognitect_aws_http.edn is found on the classpath,
  returns the symbol bound to :constructor-var.

  Throws if 0 or > 1 cognitect_aws_http.edn files are found.
  "
  []
  (let [cl   (.. Thread currentThread getContextClassLoader)
        cfgs (enumeration-seq (.getResources cl "cognitect_aws_http.edn"))]
    (case (count cfgs)
      0 (throw (RuntimeException. "Could not find cognitect_aws_http.edn on classpath."))
      1 (-> cfgs first read-config :constructor-var)

      (throw (ex-info "Found too many http-client cfgs. Pick one." {:config cfgs})))))

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
