(ns cognitect.aws.flow
  (:require [clojure.core.async :as async])
  (:import
   [java.util.concurrent CompletableFuture CompletionStage]
   [java.util.function Supplier Consumer BiConsumer]))

(defn- format-meta [m log]
  ;; TODO: (dchelimsky,2020-05-02) we're including :http-request and
  ;; :http-response on the response meta to maintain
  ;; compatibility. Consider whether there is a more general
  ;; concept/solution for this (i.e. a means of) steps registering
  ;; data to be added to metadata.
  (assoc m
         ::log log
         :http-request (->> log (map :output) (filter :http-request) last :http-request)
         :http-response (->> log (map :output) (filter :http-response) last :http-response)))

(defn redact-password [{:keys [credentials] :as log-entry}]
  (cond-> log-entry
    credentials
    (assoc-in [:credentials :aws/secret-access-key] "REDACTED")))

(defn- execute*
  [done! context log]
  (loop [context context
         log log]
    (let [q (::queue context)]
      (if-let [{:keys [name f] :as interceptor} (peek q)]
        (let [input (assoc context ::queue (pop q))
              ;; TODO (dchelimsky,2020-04-24) get rid of redact-password
              ;; once we have a log filter solution in place.
              log+ (let [beginms (System/currentTimeMillis)]
                     (fn [out]
                       (conj log {:name name
                                  :input (redact-password input)
                                  :output (redact-password out)
                                  :ms (- (System/currentTimeMillis) beginms)})))
              envelop-error (fn [t]
                              {:cognitect.anomalies/category :cognitect.anomalies/fault
                               :throwable t
                               ::failed-interceptor name})
              context (try
                        (f input)
                        (catch Throwable t
                          (envelop-error t)))]
          (cond
            (:cognitect.anomalies/category context)
            (done! (vary-meta context format-meta log))

            (instance? CompletionStage context)
            (let [reenter (reify BiConsumer
                            (accept [_ context t]
                              (let [context (or context (envelop-error t))]
                                (if (:cognitect.anomalies/category context)
                                  (done! (vary-meta context format-meta log))
                                  (execute* done! context (log+ context))))))]
              (.whenCompleteAsync ^CompletionStage context reenter))

            :else
            (recur context (log+ context))))
        (-> context
            (dissoc ::queue)
            (vary-meta format-meta log)
            done!)))))

(defn execute-future
  "Apply a queue of interceptors over a map of data

   Each interceptor is a map containing
     :name an identifier, such as a string or symbol or keyword,
     :f a function of a map returning a map

   If an interceptor's function returns a CompletableFuture, when it yields a value,
   it be will be passed to subsequent interceptors.

   The queue of remaining interceptors is available as ::queue in the input, and it
   can be dynamically manipulated.

   Returns a CompletableFuture with the last step's output
   including a trace of steps performed + timings under ::log"
  (^CompletableFuture
   [data interceptors]
    (execute-future data interceptors {:executor (java.util.concurrent.ForkJoinPool/commonPool)}))
  (^CompletableFuture
   [data interceptors {:keys [executor] :as opts}]
   (let [cf (CompletableFuture.)
         done! #(.complete cf %)
         q (into clojure.lang.PersistentQueue/EMPTY interceptors)
         data (assoc data ::queue q)
         work (fn []
                (try
                  (execute* done! data [])
                  (catch Throwable t
                    (done! {:cognitect.anomalies/category :cognitect.anomalies/fault
                            :throwable t}))))]
     (.submit ^java.util.concurrent.ExecutorService executor ^Callable work)
     cf)))

(defn execute
  "Like execute-future but returns a channel"
  ([data interceptors]
    (execute data interceptors (async/chan 1)))
  ([data interceptors ch]
   (execute data interceptors (async/chan 1) {:executor (java.util.concurrent.ForkJoinPool/commonPool)}))
  ([data interceptors ch opts]
   (let [cf (execute-future data interceptors opts)]
     (.thenAcceptAsync cf (reify java.util.function.Consumer
                            (accept [_ m]
                              (async/put! ch m))))
     ch)))

(defn submit
  [executor f]
  (java.util.concurrent.CompletableFuture/supplyAsync
   (reify java.util.function.Supplier
     (get [_] (f)))
   executor))
