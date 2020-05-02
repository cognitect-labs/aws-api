;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;      http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS-IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

;; copied and modified from cognitect.http-client

;; TODO reconsider pending ops check, when there are lazy request/response bodies

(ns ^:skip-wiki cognitect.aws.http.cognitect
  (:require
   [cognitect.aws.http :as aws]
   [clojure.core.async :refer [put!] :as a])
  (:import
   [java.net SocketTimeoutException UnknownHostException ConnectException]
   [java.io EOFException]
   [java.nio ByteBuffer]
   [java.util.concurrent RejectedExecutionException TimeUnit TimeoutException]
   [org.eclipse.jetty.client HttpClient Socks4Proxy]
   [org.eclipse.jetty.http HttpField]
   [org.eclipse.jetty.client.api Request Response Result
                                 Response$CompleteListener Response$HeadersListener Response$ContentListener]
   [org.eclipse.jetty.client.util ByteBufferContentProvider]
   [org.eclipse.jetty.util.resource Resource]
   [org.eclipse.jetty.util.ssl SslContextFactory SslContextFactory$Client]))

(set! *warn-on-reflection* true)

;; begin copied from datomic.java.io.bbuf ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn empty-bbuf
  "Returns an array-backed bbuf with pos and lim 0, cap n."
  [n]
  (.flip (ByteBuffer/wrap (byte-array n))))

(defn ^ByteBuffer unflip
  "Given a readable buffer, return a writable buffer that appends at
   the end of the buffer's valid information."
  [^ByteBuffer b]
  (-> (.duplicate b)
      (.position (.limit b))
      (.limit (.capacity b))))

(defn ^ByteBuffer expand-buffer
  "Given a readable buffer buf, returns a writeable buffer with the
   same contents as buf, plus room for extra additional bytes."
  [^ByteBuffer buf ^long extra]
  (let [available (- (.capacity buf) (.limit buf))]
    (if (<= extra available)
      (unflip buf)
      (let [new-length (max (* 2 (.capacity buf))
                            (+ extra (.capacity buf)))
            new-buf (if (.isDirect buf)
                      (ByteBuffer/allocateDirect new-length)
                      (ByteBuffer/allocate new-length))]
        (.put new-buf (.duplicate buf))))))

(defn ^ByteBuffer append-buffer
  "Given a readable buffer dest, and a readable buffer src, return
   a readable buffer that has the contents dest+src."
  [^ByteBuffer dest ^ByteBuffer src]
  (let [^ByteBuffer result (expand-buffer dest (.remaining src))]
      (.put result (.duplicate src))
      (.flip result)))
;; end copied from datomic.java.io.bbuf ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def method-string
  {:get "GET"
   :post "POST"
   :put "PUT"
   :head "HEAD"
   :delete "DELETE"
   :patch "PATCH"})

(def response-body?
  (complement #{"HEAD"}))

(defn map->jetty-request
  "Convert a Ring request map into a Jetty request. Note if :body is present
   it should be a ByteBuffer.

   See https://github.com/mmcgrana/ring/blob/master/SPEC."
  ^Request [^HttpClient jetty-client
   {:keys [server-name server-port uri query-string request-method
           scheme headers body]
    :or   {scheme "https"} :as m}]
  {:pre [(string? server-name) (integer? server-port)]}
  (let [content-type (get headers "content-type")
        req (doto (.newRequest jetty-client server-name server-port)
              (.method ^String (method-string request-method))
              (.scheme (name scheme))
              (.path ^String (if query-string
                               (str uri "?" query-string)
                               uri)))
        req (reduce-kv
             (fn [^Request req k v]
               (.header req ^String (name k) ^String v))
             req
             headers)
        req (if-let [to (::timeout-msec m)]
              (.timeout ^Request req to TimeUnit/MILLISECONDS)
              req)]
    (if body
      (.content ^Request req
        (ByteBufferContentProvider. (into-array [(.duplicate ^ByteBuffer body)])))
      req)))

(defn- on-headers
  "Helper for submit. Adds :status and :headers to state based on
   response."
  [state ^Response response]
  (let [headers (-> (reduce (fn [m ^HttpField f]
                              (assoc! m (.getLowerCaseName f) (.getValue f)))
                            (transient {})
                            (.getHeaders response))
                    persistent!)]
    (assoc state
           :status (.getStatus response)
           :headers headers)))

(defn error->category
  "Guess what categoric thing went wrong based on jetty exception.
Returns anomaly category."
  [throwable]
  (cond
    (instance? RejectedExecutionException throwable) :cognitect.anomalies/incorrect
    (instance? TimeoutException throwable) :cognitect.anomalies/unavailable
    (instance? SocketTimeoutException throwable) :cognitect.anomalies/unavailable
    (instance? ConnectException throwable) :cognitect.anomalies/unavailable
    (instance? UnknownHostException throwable) :cognitect.anomalies/not-found
    (instance? EOFException throwable) :cognitect.anomalies/unavailable))

(defn error->anomaly
  [^Throwable t]
  (if-let [cat (error->category t)]
    {:cognitect.anomalies/category cat
     :cognitect.anomalies/message (.getMessage t)}
    {:cognitect.anomalies/category :cognitect.anomalies/fault
     :cognitect.anomalies/message (.getMessage t)
     ::throwable t}))

(defn- on-complete
  "Helper for submit. Builds error map if submit failed, or Ring
response map if submit succeeded."
  [state ^Result result request]
  (merge (if (.isFailed result)
           (error->anomaly (.getFailure result))
           state)
         (select-keys request [::meta])))

(defprotocol IClient
  (submit* [_ request ch]))

(defn submit
  "Submit an http request, channel will be filled with response. Returns ch.

Request map:

:server-name        string
:server-port         integer
:uri                string
:query-string       string, optional
:request-method     :get/:post/:put/:head
:scheme             :http or :https
:headers            map from downcased string to string
:body               ByteBuffer, optional
:cognitect.http-client/timeout-msec   opt, total request send/receive timeout
:cognitect.http-client/meta           opt, data to be added to the response map

content-type must be specified in the headers map
content-length is derived from the ByteBuffer passed to body

Response map:

:status              integer HTTP status code
:body                ByteBuffer, optional
:header              map from downcased string to string
:cognitect.http-client/meta           opt, data from the request

On error, response map is per cognitect.anomalies"
  ([client request]
     (submit client request (a/chan 1)))
  ([client request ch]
   {:pre [(every? #(contains? request %) [:server-name
                                          :server-port
                                          :uri
                                          :request-method
                                          :scheme])]}
    ;; Not Clojure 1.8 compatible. Using :pre for now
    ;; (s/assert ::submit-request request)
   (submit* client request ch)))

(deftype Client
  [^HttpClient jetty-client pending-ops pending-ops-limit]
  aws/HttpClient
  (-submit [_ request ch]
   (if (< pending-ops-limit (swap! pending-ops inc))
     (do
       (put! ch (merge {:cognitect.anomalies/category :cognitect.anomalies/busy
                        :cognitect.anomalies/message (str "Ops limit reached: " pending-ops-limit)}
                       (select-keys request [::meta])))
       (swap! pending-ops dec))
     (try
       (let [jr (map->jetty-request jetty-client request)
             state (atom {})
             jr (.onResponseHeaders jr (reify Response$HeadersListener
                                         (onHeaders
                                           [_ response]
                                           (swap! state on-headers response))))
             jr (.onResponseContent jr (reify Response$ContentListener
                                         (onContent
                                           [_ response content]
                                           (swap! state on-content content))))
             listener (reify Response$CompleteListener
                        (onComplete
                          [_ result]
                          (put! ch (on-complete @state result request))
                          (swap! pending-ops dec)))]
         (.send jr listener))
       (catch RejectedExecutionException t
         (put! ch (merge {:cognitect.anomalies/category :cognitect.anomalies/unavailable
                          :cognitect.anomalies/message "Rejected by executor"}
                         (select-keys request [::meta])))
         (swap! pending-ops dec))
       (catch Throwable t
         (put! ch (merge (error->anomaly t) (select-keys request [::meta])))
         (swap! pending-ops dec))))
    ch)
  (-stop [_] (.stop jetty-client)))

(defn create
  "Creates an http-client that can be used with submit. Takes a config map with
   the following keys:

   :resolve-timeout                  in msec, default 5000
   :connect-timeout                  in msec, default 5000
   :max-connections-per-destination  default 64
   :pending-ops-limit                default 64
   :min-threads                      default 4
   :max-threads                      default 50"
  ([] (create {}))
  ([{:keys [resolve-timeout connect-timeout max-connections-per-destination
            pending-ops-limit max-threads min-threads]
      :or   {resolve-timeout 5000
             connect-timeout 5000
             max-connections-per-destination 64
             max-threads 50
             min-threads 4
             pending-ops-limit 64}
     :as   config}]
   (let [jetty-client (doto (HttpClient. (SslContextFactory$Client.))
                        (.setAddressResolutionTimeout resolve-timeout)
                        (.setConnectTimeout connect-timeout)
                        (.setMaxConnectionsPerDestination max-connections-per-destination)
                        ;; these are Jetty defaults, except for the queue size
                        (.setExecutor (doto (org.eclipse.jetty.util.thread.QueuedThreadPool.
                                             max-threads min-threads 60000
                                             (java.util.concurrent.LinkedBlockingQueue. ^int (* 2 pending-ops-limit)))
                                        (.setName "aws-api-jetty")
                                        (.setDaemon true))))]
     ;; This is jetty defaults, except daemonizing scheduler
     (.setScheduler jetty-client (org.eclipse.jetty.util.thread.ScheduledExecutorScheduler.
                                  (str (-> jetty-client class .getSimpleName) "@" (.hashCode jetty-client) "-scheduler") true))
     (.start jetty-client)
     (Client.
      jetty-client
      (atom 0)
      pending-ops-limit))))

(comment
  (a/<!! (aws/-submit c {:scheme :https
                         :server-name "lwn.net"
                         :server-port 443
                         :uri "/"
                         :request-method :get}
                      (a/chan 1))))
