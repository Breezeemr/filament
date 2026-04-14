(ns filament.impl.fork-21
  "JDK 21 preview StructuredTaskScope API: ShutdownOnFailure /
   ShutdownOnSuccess subclasses, manual throwIfFailed / result,
   joinUntil(deadline) for timeouts. Loaded only when the JDK 25
   Joiner class is not present — do not require directly from user
   code."
  (:import (java.util.concurrent Callable
                                 ExecutionException
                                 StructuredTaskScope$ShutdownOnFailure
                                 StructuredTaskScope$ShutdownOnSuccess
                                 StructuredTaskScope$Subtask
                                 TimeoutException)
           (java.time Instant Duration)))

(defn zip-all
  [ds fork-child-task]
  (with-open [scope (StructuredTaskScope$ShutdownOnFailure.)]
    (let [subs (mapv (fn [d] (.fork scope ^Callable (fork-child-task d))) ds)]
      (.join scope)
      (try
        (.throwIfFailed scope)
        (catch ExecutionException ee
          (throw (or (.getCause ee) ee))))
      (mapv (fn [^StructuredTaskScope$Subtask s] (.get s)) subs))))

(defn alt-any
  [ds fork-child-task]
  (with-open [scope (StructuredTaskScope$ShutdownOnSuccess.)]
    (doseq [d ds]
      (.fork scope ^Callable (fork-child-task d)))
    (.join scope)
    (try
      (.result scope)
      (catch ExecutionException ee
        (throw (or (.getCause ee) ee))))))

(defn with-timeout
  [d ms fork-child-task]
  (with-open [scope (StructuredTaskScope$ShutdownOnFailure.)]
    (let [sub      (.fork scope ^Callable (fork-child-task d))
          deadline (.plus (Instant/now) (Duration/ofMillis (long ms)))]
      (try
        (.joinUntil scope deadline)
        (catch TimeoutException _
          (.shutdown scope)
          (throw (TimeoutException. (str "filament timeout after " ms "ms")))))
      (try
        (.throwIfFailed scope)
        (catch ExecutionException ee
          (throw (or (.getCause ee) ee))))
      (.get ^StructuredTaskScope$Subtask sub))))
