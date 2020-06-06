(ns cognitect.aws.chanutil
  (:require [clojure.core.async :as async])
  (:import [java.io InputStream IOException]
           [java.nio ByteBuffer]))

(defn async-inputstream
  "Adapts a channel of ByteBuffers into an InputStream whose
   source input is the channel's buffers.

   The producer must close the channel when no more data is expected.

   Returns a map of:
     :inputstream, the InputStream
     :bufch, the channel where the producer puts ByteBuffers.
             if not given as argument, defaults to an unbuffered chan
     :error!, a function taking an exception indicating a producer fault.
              subsequent interaction with inputstream will throw IOException
              with the given exception as cause"
  ([] (async-inputstream (async/chan)))
  ([ch]
   (let [bb (volatile! nil)
         ex (volatile! nil)

         ;; return a non-empty buffer or nil (signifying unexceptional EOS)
         ;; or throws IOException
         current-buf #(loop [buf @bb]
                        (cond
                          ;; rethrow error first
                          @ex (throw @ex)

                          (= buf ::EOS) nil

                          (and buf (.hasRemaining ^ByteBuffer buf))
                          buf

                          :else ;; pull a buffer
                          (recur (vreset! bb (or (async/<!! ch) ::EOS)))))
         error! #(do (vreset! ex (IOException. "closed" %))
                     (async/close! ch))
         read1 #(if-let [^ByteBuffer bbuf (current-buf)]
                  (.get bbuf)
                  -1)
         readN (fn [b off len]
                 (if-let [^ByteBuffer bbuf (current-buf)]
                   (let [n (Math/min (.remaining bbuf) ^int len)]
                     (.get bbuf b off n)
                     n)
                   -1))]
     {:inputstream (proxy [InputStream] []
                     (close [] (error! nil))
                     (read
                       ([] (read1))
                       ([b] (readN b 0 (count b)))
                       ([b off len] (readN b off len))))
      :bufch ch
      :error! error!})))

(comment
  (defn test []
    (let [str->bb #(ByteBuffer/wrap (.getBytes ^String %))
          xf (comp (interpose " ") (map str->bb))
          {:keys [inputstream bufch]} (async-inputstream (async/chan 1 xf))

          f (future (slurp inputstream))]
      (doseq [s ["hello" "from" "the" "other" "side"]]
        (async/>!! bufch s))
      (async/close! bufch)
      (assert (= @f "hello from the other side"))))

  (defn test-error []
    (let [{:keys [inputstream error!]} (async-inputstream)
          _ (error! (ex-info "ohno" {:oh :no}))
          thrown (try (slurp inputstream)
                      (catch IOException ioe
                        ioe))]
      (assert (ex-data (ex-cause thrown))))))
