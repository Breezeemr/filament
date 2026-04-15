(ns filament.deferred
  "Manifold-compatible deferred API, backed by virtual threads.

  Drop-in replacement for `manifold.deferred`: switching `manifold.deferred`
  to `filament.deferred` in a `:require` form should give you the same
  surface area (chain, catch, zip, alt, timeout!, let-flow, loop, ...)
  with Filament's linear stack traces and vthread execution."
  (:refer-clojure :exclude [catch finally loop])
  (:require [clojure.string :as str]
            [filament.impl :as impl])
  (:import (filament.impl Filament)
           (java.util.concurrent Callable
                                 CancellationException
                                 CompletableFuture
                                 TimeoutException)
           (java.util.concurrent.atomic AtomicReference)))

(defonce ^:private _jdk-check
  (when (< (Long/parseLong
             (first (str/split (System/getProperty "java.specification.version") #"\.")))
           21)
    (throw (ex-info "Filament requires JDK 21 or newer"
                    {:found (System/getProperty "java.specification.version")}))))

;; Re-export the dynamic var as the *same* Var so `binding` works either way.
(.refer *ns* '*capture-traces* #'filament.impl/*capture-traces*)
(.refer *ns* '*executor* #'filament.impl/*executor*)

;; Re-export constructors / resolvers
(def filament impl/filament)
(def success-deferred impl/success-deferred)
(def error-deferred impl/error-deferred)
(def deferred impl/deferred)
(def success! impl/success!)
(def error! impl/error!)

;; Re-export the Filamentable protocol and its predicate. Extending the
;; protocol is the supported extension point for making foreign async
;; types flow through filament combinators. The built-in extensions in
;; `filament.impl` already cover `Filament`, `CompletionStage`, and any
;; `clojure.lang.IPending` value (which includes manifold deferreds),
;; so most callers never need to extend it themselves.
(.refer *ns* 'Filamentable #'filament.impl/Filamentable)
(def to-filament impl/to-filament)
(def filamentable? impl/filamentable?)

(defn ->filament
  "Adapt `x` to a Filament. If `x` satisfies `Filamentable`, dispatches
   through `to-filament`; otherwise wraps `x` as an already-realized
   success. Round-tripping an existing Filament is a no-op."
  ^filament.impl.Filament [x]
  (if (filamentable? x)
    (to-filament x)
    (impl/success-deferred x)))

(defn deferred?
  "True if `x` is directly derefable as a deferred — a Filament, a
   manifold deferred, a Clojure `promise`/`future`/`delay`, or any other
   value implementing `clojure.lang.IPending` + `clojure.lang.IDeref`.
   This is a structural check: it does not require `x` to be registered
   with the `Filamentable` protocol, and it has no compile-time or
   load-time dependency on manifold."
  [x]
  (and (instance? clojure.lang.IPending x)
       (instance? clojure.lang.IDeref x)))

(def deferrable?
  "True if `x` can be adapted to a Filament — equivalently, if `x`
   satisfies the `Filamentable` protocol. A superset of `deferred?`: the
   built-in `IPending` extension covers every `deferred?` value, and
   additional non-derefable types (e.g. a bare `CompletionStage`) are
   deferrable only because they extend the protocol. Manifold deferreds
   are recognised via the same `IPending` path, so this predicate —
   like the rest of `filament.deferred` — has no manifold dependency."
  impl/filamentable?)

(defn deref-if-deferrable
  "Internal. Derefs `x` if it is a Filament/IDeferred, otherwise returns
   it unchanged. Public only so macro expansions (e.g. `let-flow`) need
   not refer a private symbol."
  {:no-doc true}
  [x]
  (if (deferred? x) @x x))

(defn- chain-rest-on-vthread
  [seed fs]
  (impl/filament
    (fn []
      (reduce (fn [v f] (deref-if-deferrable (f v)))
              (deref-if-deferrable seed)
              fs))))

(defn chain
  "Reduce `fs` over `@d`, unwrapping any intermediate deferred. Returns a
   Filament. Fast path: if `d` is a plain value or an already-realized
   deferred, the reduction runs inline on the caller thread and only
   bails to a virtual thread if a step returns an unrealized deferred.
   A throw in any step still produces a linear stack trace — cleaner,
   in fact, since inline execution keeps the caller's frames."
  [d & fs]
  (if (and (deferred? d) (not (realized? d)))
    (chain-rest-on-vthread d fs)
    (try
      (clojure.core/loop [v  (deref-if-deferrable d)
                          fs (seq fs)]
        (if fs
          (let [r ((first fs) v)]
            (if (and (deferred? r) (not (realized? r)))
              (chain-rest-on-vthread r (next fs))
              (recur (deref-if-deferrable r) (next fs))))
          (impl/success-deferred v)))
      (catch Throwable t
        (impl/error-deferred t)))))

(defn catch
  "Returns a Filament that derefs `d`; if it throws a Throwable matching
   `err-class` (default `Throwable`), runs `handler` on the caught
   exception and uses its return value as the new result."
  ([d handler] (catch d Throwable handler))
  ([d err-class handler]
   (impl/filament
     (fn []
       (try (deref-if-deferrable d)
            (catch Throwable t
              (if (instance? err-class t)
                (deref-if-deferrable (handler t))
                (throw t))))))))

;; ---------------------------------------------------------------------------
;; Fork combinators: zip, alt, timeout!, cancel!
;;
;; Backed by java.util.concurrent.StructuredTaskScope. The API shape differs
;; between JDK 21 (ShutdownOnFailure/ShutdownOnSuccess subclasses) and JDK 25
;; (open(Joiner) factory). We detect which is present at first use and load
;; the matching impl namespace — both are preview, so --enable-preview is
;; required on either JDK.
;; ---------------------------------------------------------------------------

(def ^:private fork-impl
  (delay
    (let [ns-sym (if (try (Class/forName "java.util.concurrent.StructuredTaskScope$Joiner")
                          true
                          (catch Throwable _ false))
                   'filament.impl.fork-25
                   'filament.impl.fork-21)]
      (require ns-sym)
      {:zip-all      (ns-resolve ns-sym 'zip-all)
       :alt-any      (ns-resolve ns-sym 'alt-any)
       :with-timeout (ns-resolve ns-sym 'with-timeout)})))

(defn- attach-fork-trace!
  "Unwrap a StructuredTaskScope failure to the child's root cause,
   attach `trace` as a suppressed exception (idempotent), and return
   the unwrapped cause ready to be rethrown."
  ^Throwable [^Throwable t ^Throwable trace]
  (let [cause (impl/unwrap-fork-failure t)]
    (when trace (impl/add-suppressed-once cause trace))
    cause))

(declare cancel!)

(defn- fork-child-task
  "Build a Callable that derefs `d` and, if the surrounding scope
   interrupts the forking thread, propagates cancellation to `d`
   itself so upstream Filaments observe the interrupt rather than
   running to completion in the background."
  ^Callable [d]
  (fn []
    (try @d
         (catch InterruptedException ie
           (when (instance? Filament d) (cancel! d))
           (throw ie)))))

(defn zip
  "Run each deferred in `ds` concurrently on its own virtual thread and
   resolve to a vector of their results. If any child fails, the scope
   is cancelled and the first failure's unwrapped cause is thrown, with
   a `forked from` Throwable attached as a suppressed exception."
  [& ds]
  (let [trace (when impl/*capture-traces*
                (Throwable. "filament: forked from __filament_marker__"))
        ds    (vec ds)
        f     (:zip-all @fork-impl)]
    (impl/filament
      (fn []
        (try
          (f ds fork-child-task)
          (catch Throwable t
            (throw (attach-fork-trace! t trace))))))))

(defn alt
  "Race the deferreds in `ds`; resolve to the first successful result.
   If every child fails, the most recent failure's unwrapped cause is
   thrown (with the combinator's `forked from` trace attached)."
  [& ds]
  (let [trace (when impl/*capture-traces*
                (Throwable. "filament: forked from __filament_marker__"))
        ds    (vec ds)
        f     (:alt-any @fork-impl)]
    (impl/filament
      (fn []
        (try
          (f ds fork-child-task)
          (catch Throwable t
            (throw (attach-fork-trace! t trace))))))))

(defn timeout!
  "Return a Filament that resolves to `@d` if it completes within `ms`
   milliseconds; otherwise either throw a `TimeoutException` or (if
   `fallback` is supplied) resolve to `fallback`. The scope is cancelled
   on timeout, interrupting the child."
  ([d ms] (timeout! d ms ::no-fallback))
  ([d ms fallback]
   (let [trace (when impl/*capture-traces*
                (Throwable. "filament: timeout at __filament_marker__"))
         f     (:with-timeout @fork-impl)]
     (impl/filament
       (fn []
         (try
           (f d ms fork-child-task)
           (catch Throwable t
             (let [cause (impl/unwrap-fork-failure t)]
               (if (and (instance? TimeoutException cause)
                        (not= fallback ::no-fallback))
                 fallback
                 (do (when trace (impl/add-suppressed-once cause trace))
                     (throw cause)))))))))))

(defn cancel!
  "Best-effort cancellation: completes `d`'s backing CompletableFuture
   with a `CancellationException` and, if a body is currently running,
   interrupts its virtual thread. Returns nil."
  [^Filament d]
  (let [cf     ^CompletableFuture (.cf d)
        runner ^AtomicReference   (.runner d)]
    (when-not (.isDone cf)
      (.completeExceptionally cf (CancellationException. "filament cancelled")))
    (when-let [^Thread t (some-> runner .get)]
      (when-not (identical? t (Thread/currentThread))
        (.interrupt t)))
    nil))

(defn realized?
  "True if `d` is a realized deferred. Works on Filaments, manifold
   deferreds, Clojure `promise`/`future`/`delay`, and anything else
   implementing `clojure.lang.IPending`."
  [d]
  (and (instance? clojure.lang.IPending d)
       (clojure.core/realized? ^clojure.lang.IPending d)))

(defn finally
  "Returns a Filament that derefs `d`, runs the 0-arg side-effecting `f`
   regardless of success or failure, and propagates the original
   value/error."
  [d f]
  (impl/filament
    (fn []
      (try (deref-if-deferrable d)
           (finally (f))))))

;; ---------------------------------------------------------------------------
;; Sugar: let-flow and loop
;; ---------------------------------------------------------------------------

(defmacro let-flow
  "Like `let`, but every binding value is derefed if it is a
   Filament/IDeferred. Expands to a `filament` body running a plain
   `let`, so bindings execute sequentially on a single vthread and a
   throw in any binding produces a linear stack trace.

   Unlike Manifold's `let-flow`, bindings are NOT reordered or
   parallelised: they run in source order."
  [bindings & body]
  (assert (vector? bindings) "let-flow requires a vector of bindings")
  (assert (even? (count bindings)) "let-flow requires an even number of binding forms")
  (let [pairs   (partition 2 bindings)
        derefed (mapcat (fn [[sym expr]]
                          [sym `(filament.deferred/deref-if-deferrable ~expr)])
                        pairs)]
    `(filament.deferred/filament
       (fn [] (let [~@derefed] ~@body)))))

(defmacro loop
  "Manifold-compat alias: wraps `clojure.core/loop` in a `filament` body
   so `(d/loop [i 0] ...)` reads like `manifold.deferred/loop`. On a
   vthread `recur` is just `recur`; there's no callback hop."
  [bindings & body]
  `(filament.deferred/filament
     (fn [] (clojure.core/loop ~bindings ~@body))))
