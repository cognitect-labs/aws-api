(ns cognitect.aws.http.java-net
  (:require [cognitect.aws.http :as aws-http]
            [clojure.core.async :as async]
            [clojure.string :as string])
  (:import [java.net URI]
           [java.net.http
            HttpClient HttpRequest$BodyPublishers HttpRequest HttpResponse$BodyHandlers]
           (java.io IOException)
           (java.util.function Function)
           (java.time Duration)))

(defn ^:private dissoc-by
  [pred m]
  (apply dissoc m (filter pred (keys m))))

(def ^:private restricted-headers
     #{:host :accept-charset :accept-encoding :access-control-request-headers
       :access-control-request-method :connection :content-length :cookie :date :dbt :expect
       :feature-policy :origin :keep-alive :referer :te :trailer :transfer-encoding :upgrade :via})

(defmacro java-fn ^Function [argv & body]
  `(reify Function
     (apply [_# x#] ((fn ~argv ~@body) x#))))

(def ^:private method-string
     {:get "GET"
      :post "POST"
      :put "PUT"
      :head "HEAD"
      :delete "DELETE"
      :patch "PATCH"})

(defn body->body-publisher
  [body]
  (cond
    (nil? body)
    (HttpRequest$BodyPublishers/noBody)

    (instance? (Class/forName "[B") body)
    (HttpRequest$BodyPublishers/ofByteArray body)

    :else
    (HttpRequest$BodyPublishers/noBody)))

(defn request->complete-uri
  [{:keys [scheme server-name server-port uri query-string]
    :or {scheme "https"}}]
  (str (name scheme) "://"
       server-name
       (when server-port (str ":" server-port))
       uri
       (when query-string (str "?" query-string))))

(defn remove-restricted-headers
  "Remove restricted headers.
  More info:
    - https://www.rfc-editor.org/rfc/rfc7230#section-3.2
    - https://developer.mozilla.org/en-US/docs/Glossary/Forbidden_header_name"
  [headers]
  (->> headers
       (dissoc-by #(contains? restricted-headers
                              (keyword (string/replace (string/lower-case (name %)) #" " "-"))))
       (dissoc-by #(string/starts-with? (string/lower-case (name %)) "sec-"))
       (dissoc-by #(string/starts-with? (string/lower-case (name %)) "proxy-"))))

(defn ^:private build-headers
  [java-net-http-request
   {:keys [headers]}]
  (reduce-kv
    (fn [req key value]
      (.header req (name key) value))
    java-net-http-request
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
     ::throwable                   t}

    {:cognitect.anomalies/category :cognitect.anomalies/fault
     :cognitect.anomalies/message (.getMessage t)
     ::throwable t}))

(defn request->java-net-http-request
  "Build a java.net.HttpRequest based on an aws client request"
  [{:keys [scheme body request-method timeout-msec]
    :or   {scheme "https"
           timeout-msec 3000} :as request}]
  (let [body-publisher (body->body-publisher body)
        uri            (request->complete-uri request)
        http-method    (method-string request-method)
        timeout        (Duration/ofMillis timeout-msec)]
    (-> (doto (HttpRequest/newBuilder)
          (.method http-method body-publisher)
          (.uri (URI/create uri))
          (.timeout timeout)
          (build-headers request))
        (.build))))

(defn format-response-headers
  "Java HTTP Client always return values as arrays. This transforms array of a single item to a single value"
  [response]
  (into {}
        (map (fn [[key value]] [key (if (> (count value) 1) (vec value) (first value))]))
        (.map (.headers response))))

(defn handle-response
  [response channel meta]
  (async/put! channel
              {:status  (.statusCode response)
               :body    (.body response)
               :headers (format-response-headers response)
               :meta    meta}))

(defn submit
  [client request channel]
  (try
    (let [java-request (request->java-net-http-request request)
          req (.sendAsync client java-request (HttpResponse$BodyHandlers/ofInputStream))
          meta (:meta request)]
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
  (let [java-http-client (HttpClient/newHttpClient)]
    (reify aws-http/HttpClient
      (-submit [_ request channel]
        (submit java-http-client request channel))
      (-stop [_]
        (stop java-http-client)))))
