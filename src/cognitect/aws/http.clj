;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.http
  "Impl, don't call directly."
  (:require [clojure.core.async :as a]
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
     :body                   byte[], optional
     :timeout-msec           opt, total request send/receive timeout
     :meta                   opt, data to be added to the response map

     content-type must be specified in the headers map

     Response map:

     :status            integer HTTP status code
     :body              InputStream, optional
     :headers           map from downcased string to string
     :meta              opt, data from the request

     On error, response map is per cognitect.anomalies.")
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

(def ^:private cognitect-http-client-ref
     (delay (dynaload/load-var 'cognitect.aws.http.cognitect/create)))

(defn resolve-http-client
  [http-client-or-sym]
  (let [c (cond (client? http-client-or-sym)
                http-client-or-sym

                (fn? http-client-or-sym)
                (http-client-or-sym)

                :else
                (@cognitect-http-client-ref))]
    (when-not (client? c)
      (throw (ex-info "not an http client" {:provided http-client-or-sym
                                            :resolved c})))
    c))
