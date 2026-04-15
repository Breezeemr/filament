(ns filament.impl
  (:import (java.util.concurrent CompletableFuture
                                 CompletionStage
                                 ExecutionException
                                 ExecutorService
                                 Executors
                                 TimeUnit
                                 TimeoutException)
           (java.util.concurrent.atomic AtomicReference)
           (java.util.function BiConsumer)))

(def ^:dynamic *capture-traces*
  "When true (the default) Filament captures a Throwable at every submit
   site and every cross-thread deref, and attaches it as a suppressed
   exception on the propagated error."
  true)

(def ^:dynamic *executor*
  "Default executor used by (filament f) when no explicit executor is
   passed. nil means the shared vthread-per-task executor."
  nil)

(def ^:private marker "__filament_marker__")

(def default-executor
  (delay (Executors/newVirtualThreadPerTaskExecutor)))

(definterface IFilamentInternal
  (^java.util.concurrent.CompletableFuture cf [])
  (^java.lang.Throwable submitTrace [])
  (^java.util.concurrent.atomic.AtomicReference runner []))

(defn unwrap-fork-failure
  "Walk `t` toward the root cause, peeling off StructuredTaskScope
   wrappers (FailedException / ExecutionException) so callers see the
   child's own exception."
  ^Throwable [^Throwable t]
  (loop [^Throwable cur t]
    (let [cls (when cur (.getName (class cur)))]
      (if (and cur
               (or (= cls "java.util.concurrent.StructuredTaskScope$FailedException")
                   (= cls "java.util.concurrent.ExecutionException"))
               (some? (.getCause cur)))
        (recur (.getCause cur))
        cur))))

(defn add-suppressed-once
  "Add `suppressed` as a suppressed exception on `target` unless one with
   the filament marker is already present. Idempotent."
  [^Throwable target ^Throwable suppressed]
  (when (and target suppressed)
    (let [msg      (.getMessage suppressed)
          existing (.getSuppressed target)
          already? (some (fn [^Throwable s]
                           ;; Dedup identical synthetic filament traces:
                           ;; same identity, or same marker-bearing
                           ;; message. Different filament labels
                           ;; (e.g. "deref site" and "forked from")
                           ;; have distinct messages and both survive.
                           (or (identical? s suppressed)
                               (let [m (.getMessage s)]
                                 (and m msg
                                      (.contains ^String m marker)
                                      (= m msg)))))
                         existing)]
      (when-not already?
        (.addSuppressed target suppressed))))
  target)

(defn- maybe-attach-deref-trace
  [^Throwable cause ^AtomicReference runner]
  (when (and *capture-traces* runner)
    (let [r (.get runner)]
      (when (and r (not (identical? r (Thread/currentThread))))
        (add-suppressed-once
          cause
          (Throwable. (str "filament: deref site " marker))))))
  cause)

(defn unwrap-get
  "Blocking .get on cf. Unwraps ExecutionException → its cause.
   On cross-thread deref (runner != current thread) and *capture-traces*,
   attaches a deref-site Throwable as suppressed."
  [^CompletableFuture cf _submit-trace ^AtomicReference runner]
  (try
    (.get cf)
    (catch ExecutionException ee
      (let [cause (.getCause ee)]
        (maybe-attach-deref-trace cause runner)
        (throw cause)))))

(defn unwrap-get-timed
  [^CompletableFuture cf _submit-trace ^AtomicReference runner timeout-ms timeout-val]
  (try
    (.get cf (long timeout-ms) TimeUnit/MILLISECONDS)
    (catch TimeoutException _
      timeout-val)
    (catch ExecutionException ee
      (let [cause (.getCause ee)]
        (maybe-attach-deref-trace cause runner)
        (throw cause)))))

(deftype Filament [^CompletableFuture cf
                   ^Throwable submit-trace
                   ^AtomicReference runner
                   _meta]
  IFilamentInternal
  (cf [_] cf)
  (submitTrace [_] submit-trace)
  (runner [_] runner)

  clojure.lang.IDeref
  (deref [_] (unwrap-get cf submit-trace runner))

  clojure.lang.IBlockingDeref
  (deref [_ timeout-ms timeout-val]
    (unwrap-get-timed cf submit-trace runner timeout-ms timeout-val))

  clojure.lang.IPending
  (isRealized [_] (.isDone cf))

  clojure.lang.IObj
  (meta [_] _meta)
  (withMeta [_ m] (Filament. cf submit-trace runner m)))

(defn filament
  ([f] (filament f nil))
  ([f executor]
   (let [cf     (CompletableFuture.)
         runner (AtomicReference.)
         trace  (when *capture-traces*
                  (Throwable. (str "filament: submit site " marker)))
         exec   ^ExecutorService (or executor *executor* @default-executor)
         ;; Capture the caller's dynamic-var frame so bindings established
         ;; around the filament call site are visible inside the body on
         ;; its vthread. Matches clojure.core/future-call / bound-fn*.
         frame  (clojure.lang.Var/getThreadBindingFrame)
         body   ^Runnable (fn []
                            (clojure.lang.Var/resetThreadBindingFrame frame)
                            (.set runner (Thread/currentThread))
                            (try
                              (.complete cf (f))
                              (catch Throwable t
                                (.completeExceptionally cf t))))]
     ;; NOTE: runner is intentionally left set after completion so that
     ;; cross-thread deref detection works deterministically. Task 04
     ;; (cancellation) will gate interruption on (.isDone cf).
     (.submit exec body)
     (->Filament cf trace runner nil))))

(defn success-deferred
  [v]
  (->Filament (CompletableFuture/completedFuture v) nil (AtomicReference.) nil))

(defn error-deferred
  [^Throwable e]
  (let [cf (CompletableFuture.)]
    (.completeExceptionally cf e)
    (->Filament cf nil (AtomicReference.) nil)))

(defn deferred
  []
  (->Filament (CompletableFuture.) nil (AtomicReference.) nil))

(defn success!
  [^Filament d v]
  (.complete ^CompletableFuture (.cf d) v))

(defn error!
  [^Filament d ^Throwable e]
  (.completeExceptionally ^CompletableFuture (.cf d) e))

;; ---------------------------------------------------------------------------
;; Filamentable protocol
;;
;; Open extension point for adapting foreign async values to Filaments.
;; Extend this to your own async types and they flow through
;; filament.deferred combinators; `filament.manifold` extends it to
;; manifold's IDeferred so manifold deferreds bridge without a hard
;; dependency here.
;; ---------------------------------------------------------------------------

(defprotocol Filamentable
  "Protocol for values that can be adapted to a Filament."
  (to-filament [x]
    "Return a Filament that resolves to the eventual value of `x`. Must
     return a `filament.impl.Filament`; callers rely on that concrete
     type for deref/cancel/linear-trace semantics."))

(defn- complete-stage-bridge
  ^Filament [^CompletionStage stage]
  (let [d (deferred)]
    (.whenComplete stage
      (reify BiConsumer
        (accept [_ v e]
          (if e
            (error! d (if-let [c (.getCause ^Throwable e)] c e))
            (success! d v)))))
    d))

(extend-protocol Filamentable
  Filament
  (to-filament [x] x)

  CompletableFuture
  (to-filament [x] (complete-stage-bridge x))

  CompletionStage
  (to-filament [x] (complete-stage-bridge x))

  ;; Structural catch-all for Clojure-side derefable deferreds:
  ;; manifold `Deferred`, `clojure.core/promise`, `future`, `delay`, and
  ;; any user type that implements `IPending`. This is what lets
  ;; `filament.deferred` work with manifold deferreds without a compile-
  ;; time or load-time manifold dependency. The realized fast path
  ;; avoids spinning up a vthread for values that are already available;
  ;; the unrealized path hands off to a vthread so callers don't block.
  clojure.lang.IPending
  (to-filament [x]
    (if (clojure.core/realized? x)
      (try (success-deferred (deref x))
           (catch Throwable t (error-deferred t)))
      (filament (fn [] (deref x))))))

(defn filamentable?
  "True if `x` satisfies the `Filamentable` protocol — i.e. `to-filament`
   knows how to adapt it."
  [x]
  (satisfies? Filamentable x))
