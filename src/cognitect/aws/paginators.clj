;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.paginators
  (:require [cognitect.aws.client :as client]
            [cognitect.aws.client.api.async :as api.async]))

(defn builder
  "Return a paginator builder.

  This function should be used by the API layer to build paginators
  for every operation that support them.

  A paginator is a transduce-like operation to apply transducers
  to each aws response of a paginated aws request.

  The descriptor map accepts the following keys:
  :paginator/input-token    The parameter to specify the offset of the next request
  :paginator/output-token   Where to find the offset's value in the previous request
  :paginator/limit-key      Where to put the limit's value (optional)
  :paginator/result-key     Where to find the result of the aws response (optional)"
  [{:keys [paginator/input-token paginator/output-token paginator/result-key]}]
  (fn [client op-map callback]
    (fn [xform rf init]
      (let [rf (xform rf)
            step (fn step [acc op-map]
                   (api.async/invoke client
                                      (update op-map
                                              ::client/callback
                                              (fn [callback]
                                                (fn [response]
                                                  (if (:cognitect.anomalies/category response)
                                                    (callback (assoc response :paginator/state acc))
                                                    (let [acc (rf acc (if result-key
                                                                        (get response result-key)
                                                                        response))
                                                          next-token (get response output-token)]
                                                      (cond
                                                        (reduced? acc)
                                                        (callback (rf @acc))

                                                        next-token
                                                        (step acc (update op-map :request assoc input-token next-token))

                                                        :else
                                                        (callback (rf acc))))))))))]
        (step init op-map)))))
