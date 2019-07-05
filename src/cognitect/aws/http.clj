(ns cognitect.aws.http
  (:require [clojure.edn :as edn]
            [clojure.core.async :as a]))

(defprotocol HttpClient
  (-submit [_ request channel]
    "Submit an http request, channel will be filled with response. Returns ch.

     Request map:

     :protocol               :http or :https
     :server-name            string
     :server-port            integer
     :path                   string
     :query-string           string, optional
     :request-method         :get/:post/:put/:head/:delete
     :headers                map from downcased string to string
     :body                   ByteBuffer, optional
     :timeout-msec           opt, total request send/receive timeout
     :meta                   opt, data to be added to the response map
     :response-chunk-size TBD

     content-type must be specified in the headers map
     content-length is derived from the ByteBuffer passed to body

     Response map:

     :status            integer HTTP status code
     :body              ByteBuffer, optional
     :headers           map from downcased string to string
     :meta              opt, data from the request

     On error, response map is per cognitect.anomalies")
  (-stop [_] "Stops the client, releasing resources"))

(defn submit
  ([client request]
   (-submit client request (a/chan 1)))
  ([client request channel]
   (-submit client request channel)))

(defn client?
  [c]
  (satisfies? HttpClient c))

(defn read-config
  [url]
  (-> url slurp edn/read-string))

(defn dynaload-client
  []
  (let [cl (.. Thread currentThread getContextClassLoader)
        found (enumeration-seq (.getResources cl "cognitect_aws_http.edn"))]
    (case (count found)
      0 (throw (ex-info "no clients found" {}))
      1 ((requiring-resolve (-> found first read-config :constructor-var)))

      (throw (ex-info "too many http clients, pick one" {:found found})))))

(defn resolve-http-client
  [http-client]
  (let [c (or (when (symbol? http-client)
                ((requiring-resolve http-client)))
              http-client
              (dynaload-client))]
    (when-not (client? c)
      (throw (ex-info "not an http client" {:provided http-client
                                            :resolved c})))
    c))

;; TODO consider providing config arguments to http constructor
