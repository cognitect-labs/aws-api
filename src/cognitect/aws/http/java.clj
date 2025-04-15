(ns ^:skip-wiki cognitect.aws.http.java
  (:require [cognitect.aws.http :as aws-http]
            [clojure.core.async :as async]
            [clojure.string :as string])
  (:import [java.io IOException]
           [java.net URI]
           [java.net.http
            HttpClient HttpClient$Redirect HttpRequest HttpRequest$Builder
            HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers]
           [java.nio ByteBuffer]
           [java.time Duration]
           [java.util.function Function]))

(set! *warn-on-reflection* true)

(defn ^:private dissoc-by
  [pred m]
  (apply dissoc m (filter pred (keys m))))

(def ^:private restricted-headers
  "The headers disallowed by JDK's HttpClient"
  #{:connection :content-length :expect :host :upgrade})

(defmacro java-fn ^Function [argv & body]
  `(reify Function
     (apply [_# x#] ((fn ~argv ~@body) x#))))

(def ^:private method-string
  {:get    "GET"
   :post   "POST"
   :put    "PUT"
   :head   "HEAD"
   :delete "DELETE"
   :patch  "PATCH"})

(defn http-client
  "Create and return a java.net.http.HttpClient with some reasonable defaults"
  []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofMillis 10000))
      (.followRedirects HttpClient$Redirect/NEVER)
      (.build)))

(defn body->body-publisher
  [^ByteBuffer body]
  (if (nil? body)
    (HttpRequest$BodyPublishers/noBody)
    (HttpRequest$BodyPublishers/ofByteArray (.array body))))

(defn request->complete-uri
  "Builds and returns a java.net.URI from the request map."
  [{:keys [scheme server-name server-port uri query-string]
    :or   {scheme "https"}}]
  (let [;; NOTE: we can't use URI's constructor passing individual components, because sometimes
        ;;       the `:uri` part includes query params
        ;;       (e.g. on DeleteObjects op, :uri is `/bucket-name?delete`)
        full-uri (str (name scheme) "://"
                      (aws-http/uri-authority scheme server-name server-port)
                      uri
                      (when query-string (str "?" query-string)))]
    (URI/create full-uri)))

(defn remove-restricted-headers
  "Remove any headers that are disallowed by JDK HttpClient (because HttpClient will determine their
  values itself).

  More info:
    - https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/module-summary.html
    - https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html#header(java.lang.String,java.lang.String)
    - https://www.rfc-editor.org/rfc/rfc7230#section-3.2

  (Not to be confused with the unrelated topic of headers that are restricted from use by developers
  in a browser/XmlHttpRequest context.)"
  [headers]
  (->> headers
       (dissoc-by #(contains? restricted-headers (-> %
                                                     name
                                                     string/lower-case
                                                     keyword)))))

(defn ^:private build-headers
  [java-net-http-request-builder
   {:keys [headers]}]
  (reduce-kv
   (fn [^HttpRequest$Builder req key value]
     (.header req ^String (name key) ^String value))
   java-net-http-request-builder
   (remove-restricted-headers headers)))

(defn ^:private error->category
  "Guess what category thing went wrong based on exception.
  Returns anomaly category.

  This docs contains all possible exception that can be thrown by java.net.http
  https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpClient.html"
  [throwable]
  (cond
    (instance? IOException throwable) :cognitect.anomalies/fault

    (instance? IllegalArgumentException throwable) :cognitect.anomalies/incorrect

    (instance? SecurityException throwable) :cognitect.anomalies/forbidden

    :else :cognitect.anomalies/fault))

(defn error->anomaly
  [^Throwable t]
  (if-let [category (error->category t)]
    {:cognitect.anomalies/category category
     :cognitect.anomalies/message  (.getMessage t)
     :cognitect.aws/throwable      t}

    {:cognitect.anomalies/category :cognitect.anomalies/fault
     :cognitect.anomalies/message  (.getMessage t)
     :cognitect.aws/throwable      t}))

(defn request->java-net-http-request
  "Build a java.net.HttpRequest based on an aws client request"
  [{:keys [body request-method timeout-msec]
    :or {timeout-msec 0} :as request}]
  (let [body-publisher (body->body-publisher body)
        uri (request->complete-uri request)
        http-method (method-string request-method)]
    (cond-> (doto (HttpRequest/newBuilder)
              (.method http-method body-publisher)
              (.uri ^java.net.URI uri)
              (build-headers request))
      ;; optionally set read response timeout aka idle timeout
      ;; unset means unbounded
      ;; builder treats any duration less than or equal to zero as illegal argument
      (pos? timeout-msec) (.timeout (Duration/ofMillis timeout-msec))
      :finally (.build))))

(defn format-response-headers
  "Java HTTP Client always return values as arrays. This transforms array of a single item to a single value"
  [^HttpResponse response]
  (into {}
        (map (fn [[key value]] [key (if (> (count value) 1) (vec value) (first value))]))
        (.map (.headers response))))

(defn handle-response
  [^HttpResponse response channel meta]
  (async/put! channel
              {:status  (.statusCode response)
               :body    (ByteBuffer/wrap (.body response))
               :headers (format-response-headers response)
               :meta    meta}))

(defn submit
  [^HttpClient client request channel]
  (try
    (let [java-request (request->java-net-http-request request)
          req          (.sendAsync client java-request (HttpResponse$BodyHandlers/ofByteArray))
          meta         (:meta request)]
      (->
       (.thenApply req
                   (java-fn [response]
                            (handle-response response channel meta)
                            response))
       (.exceptionally (java-fn [ex]
                                (async/put! channel
                                            (error->anomaly ex)))))

      channel)
    (catch Exception ex
      (async/put! channel
                  (error->anomaly ex)))))

(defn stop
  [_]
  "Not implemented")

(defn create
  []
  (let [java-http-client (http-client)]
    (reify aws-http/HttpClient
      (-submit [_ request channel]
        (submit java-http-client request channel))
      (-stop [_]
        (stop java-http-client)))))
