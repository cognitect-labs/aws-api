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

;; heavily adapted from cognitect.http-client

;; TODO reconsider pending ops check, when there are lazy request/response bodies

(ns ^:skip-wiki cognitect.aws.http.cognitect
  (:require
   [cognitect.aws.http :as aws]
   [cognitect.aws.chanutil :as chanutil]
   [clojure.core.async :refer [put!] :as a])
  (:import
   [java.net SocketTimeoutException UnknownHostException ConnectException]
   [java.io EOFException]
   [java.nio ByteBuffer]
   [java.util.concurrent RejectedExecutionException TimeUnit TimeoutException]
   [org.eclipse.jetty.client HttpClient Socks4Proxy]
   [org.eclipse.jetty.http HttpField]
   [org.eclipse.jetty.client.api Request Response Result
    Response$HeadersListener
    Response$AsyncContentListener
    Response$CompleteListener]
   [org.eclipse.jetty.client.util ByteBufferContentProvider]
   [org.eclipse.jetty.util Callback]
   [org.eclipse.jetty.util.resource Resource]
   [org.eclipse.jetty.util.ssl SslContextFactory SslContextFactory$Client]))

(set! *warn-on-reflection* true)

(defn copy-bbuf
  [^ByteBuffer bb]
  (-> (ByteBuffer/allocate (.remaining bb))
      (.put bb)
      .flip))

(def method-string
  {:get "GET"
   :post "POST"
   :put "PUT"
   :head "HEAD"
   :delete "DELETE"
   :patch "PATCH"})

(defn add-request-body
  ^Request [^Request req {:keys [body request-body-as]}]
  ;; TODO, dispatch on request-body-as
  (cond-> req
    body (.content (ByteBufferContentProvider. (into-array [(.duplicate ^ByteBuffer body)])))))

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
    req))

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

(comment
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
  :response-body-as   :inputstream / :chan
  :cognitect.http-client/timeout-msec   opt, total request send/receive timeout
  :cognitect.http-client/meta           opt, data to be added to the response map

  content-type must be specified in the headers map
  content-length is derived from the ByteBuffer passed to body

  Response map:

  :status              integer HTTP status code
  :body                ByteBuffer, optional
  :header              map from downcased string to string
  :cognitect.http-client/meta           opt, data from the request

  On error, response map is per cognitect.anomalies")

(defmulti response-listener
  "attach callbacks to jetty-request and return
   a fn of jetty Result"
  (fn [jetty-req request ch pending-ops]
    (get request :response-body-as :inputstream)))

(defn headers
  [^Response response]
  (-> (reduce (fn [m ^HttpField f]
                (assoc! m (.getLowerCaseName f) (.getValue f)))
              (transient {})
              (.getHeaders response))
      persistent!))

(defn respond!
  [ch latch {:keys [response-body-as] :as request} body]
  (reify Response$HeadersListener
    (onHeaders [_ response]
      (when (compare-and-set! latch false true)
        (put! ch (merge {:status (.getStatus response)
                         :headers (headers response)
                         :body body
                         :response-body-as (or response-body-as :inputstream)}
                        (select-keys request [::meta])))))))

(defn content->ch
  [ch]
  (reify Response$AsyncContentListener
    (onContent [_ response content callback]
      (put! ch (copy-bbuf content)
            ;; signal Jetty when put! into channel completes
            #(if %
               (.succeeded callback)
               ;; user closed InputStream
               (.failed callback (java.nio.channels.AsynchronousCloseException.)))))))

(defmethod response-listener
  :chan
  [^Request jr request respch pending-ops]
  ;; HTTP makes it possible to receive a response before the fully sending the request.
  ;; AWS always checks request checksum, so this possibility does not have to be accounted for

  ;; However, it is possible to fail before receiving response headers,
  ;; so keep track of whether we sent to the channel
  (let [latch (atom false)
        bufch (a/chan)
        on-complete (reify Response$CompleteListener
                      (onComplete [_ result]
                        (when-let [ex (and (.isFailed result) (.getFailure result))]
                          (when (compare-and-set! latch false true)
                            (put! respch (merge (error->anomaly ex)
                                                (select-keys request [::meta])))))
                        ;; close channel buffer
                        (a/close! bufch)
                        (swap! pending-ops dec)))]
    (-> jr
        (.onResponseHeaders (respond! respch latch request bufch))
        (.onResponseContentAsync (content->ch bufch)))
    on-complete))

(defmethod response-listener
  :inputstream
  [^Request jr request respch pending-ops]
  ;; HTTP makes it possible to receive a response before the fully sending the request.
  ;; AWS always checks request checksum, so this possibility does not have to be accounted for

  ;; However, it is possible to fail before receiving response headers,
  ;; so keep track of whether we sent to the channel
  (let [latch (atom false)
        {:keys [bufch inputstream error!]} (chanutil/async-inputstream)
        on-complete (reify Response$CompleteListener
                      (onComplete [_ result]
                        (when-let [ex (and (.isFailed result) (.getFailure result))]
                          (error! ex)
                          (when (compare-and-set! latch false true)
                            (put! respch (merge (error->anomaly ex)
                                            (select-keys request [::meta])))))
                        ;; close inputstream buffer
                        (a/close! bufch)
                        (swap! pending-ops dec)))]
    (-> jr
        (.onResponseHeaders (respond! respch latch request inputstream))
        (.onResponseContentAsync (content->ch bufch)))
    on-complete))

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
       (let [jr (-> (map->jetty-request jetty-client request)
                    (add-request-body request))]
         (.send jr (response-listener jr request ch pending-ops)))
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
  (def c (create))
  (def req {:scheme :https
            :server-name "lwn.net"
            :server-port 443
            :uri "/"
            :request-method :get})
  (a/<!! (aws/-submit c req (a/chan 1)))

  )
