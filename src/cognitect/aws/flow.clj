(ns cognitect.aws.flow
  (:require [clojure.core.async :as async])
  (:import
   [java.util.concurrent CompletableFuture CompletionStage]
   [java.util.function Supplier Consumer BiConsumer]))

(defn- execute*
  [done! context log]
  (loop [context context
         log log]
    (let [q (::queue context)]
      (if-let [{:keys [name f] :as interceptor} (peek q)]
        (let [input (assoc context ::queue (pop q))
              log+ (let [beginms (System/currentTimeMillis)]
                     #(conj log {:name name
                                 :ms (- (System/currentTimeMillis) beginms)}))
              envelop-error (fn [t]
                             {:cognitect.anomalies/category :cognitect.anomalies/fault
                              :throwable t
                              :failing-input input
                              ::queue (pop q)
                              ::failing-step name})
              context (try
                         (f input)
                         (catch Throwable throwable
                           (envelop-error throwable)))]
          (cond
            (:cognitect.anomalies/category context)
            (done! (assoc context ::log log))

            (instance? CompletionStage context)
            (let [reenter (reify BiConsumer
                            (accept [_ ctx t]
                              (let [ctx (if t (envelop-error t) ctx)]
                                (execute* done! ctx (log+)))))]
              (.whenCompleteAsync ^CompletionStage context reenter))

            :else
            (recur context (log+))))
        (-> context
            (dissoc ::queue)
            (assoc ::log log)
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
  ^CompletableFuture
  [data interceptors]
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
    (.submit (java.util.concurrent.ForkJoinPool/commonPool) ^Callable work)
    cf))

(defn execute
  "Like execute-future but returns a channel"
  ([data interceptors]
    (execute data interceptors (async/chan 1)))
  ([data interceptors ch]
   (let [cf (execute-future data interceptors)]
     (.thenAcceptAsync cf (reify java.util.function.Consumer
                            (accept [_ m]
                              (async/put! ch m))))
     ch)))
