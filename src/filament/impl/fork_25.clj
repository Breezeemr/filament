(ns filament.impl.fork-25
  "JDK 25 preview StructuredTaskScope API: open(Joiner) factory,
   Joiner.allSuccessfulOrThrow / anySuccessfulResultOrThrow, withTimeout
   on the config function. Loaded only when the Joiner class is
   present — do not require directly from user code."
  (:import (java.util.concurrent Callable
                                 StructuredTaskScope
                                 StructuredTaskScope$Joiner
                                 StructuredTaskScope$Subtask
                                 StructuredTaskScope$TimeoutException
                                 TimeoutException)
           (java.time Duration)
           (java.util.function Function)))

(defn zip-all
  [ds fork-child-task]
  (with-open [scope (StructuredTaskScope/open
                      (StructuredTaskScope$Joiner/allSuccessfulOrThrow))]
    (let [subs (mapv (fn [d] (.fork scope ^Callable (fork-child-task d))) ds)]
      (.join scope)
      (mapv (fn [^StructuredTaskScope$Subtask s] (.get s)) subs))))

(defn alt-any
  [ds fork-child-task]
  (with-open [scope (StructuredTaskScope/open
                      (StructuredTaskScope$Joiner/anySuccessfulResultOrThrow))]
    (doseq [d ds]
      (.fork scope ^Callable (fork-child-task d)))
    (.join scope)))

(defn with-timeout
  [d ms fork-child-task]
  (try
    (with-open [scope (StructuredTaskScope/open
                        (StructuredTaskScope$Joiner/allSuccessfulOrThrow)
                        (reify Function
                          (apply [_ cfg]
                            (.withTimeout cfg (Duration/ofMillis (long ms))))))]
      (let [sub (.fork scope ^Callable (fork-child-task d))]
        (.join scope)
        (.get ^StructuredTaskScope$Subtask sub)))
    (catch StructuredTaskScope$TimeoutException te
      (throw (doto (TimeoutException. (str "filament timeout after " ms "ms"))
               (.initCause te))))))
