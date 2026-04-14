(ns filament.manifold
  "Optional interop bridge between Filament and Manifold.

  Load order matters: `(require 'filament.core)` must precede
  `(require 'filament.manifold)`, because this namespace extends the
  already-loaded `filament.impl/Filament` class with manifold's
  `Deferrable` protocol.

  `filament.core` has no compile-time or load-time dependency on
  manifold; this namespace is the only place that imports it."
  (:require [filament.core :as f]
            [filament.impl :as impl]
            [manifold.deferred :as md])
  (:import (filament.impl Filament)))

;; ---------------------------------------------------------------------------
;; Why not extend manifold.deferred/IDeferred directly?
;;
;; In manifold 0.4.x `IDeferred` is a `definterface+`, i.e. a real Java
;; interface, not a Clojure protocol. You cannot `extend` a Java interface
;; onto an already-compiled deftype after the fact. So we take two steps:
;;
;; 1. Extend manifold's `Deferrable` *protocol* (which IS a protocol) so
;;    that `md/->deferred` on a Filament produces a genuine manifold
;;    deferred that mirrors the Filament's eventual state.
;; 2. Rely on the fact that Filament already implements IPending + IDeref,
;;    which manifold's `deferrable?` also recognises: so manifold's `chain`
;;    and friends accept a Filament as an input value out of the box (they
;;    call `->deferred` internally, which hits the Deferrable protocol we
;;    just extended).
;;
;; The net effect is the same as extending IDeferred would be: a Filament
;; flows through manifold's combinators, and vice versa, with proper
;; error unwrapping in both directions.
;; ---------------------------------------------------------------------------

(extend-protocol md/Deferrable
  Filament
  (to-deferred [^Filament fil]
    (let [out (md/deferred)]
      ;; Bridge via CompletableFuture.whenComplete so we don't depend on
      ;; filament.core/on-realized (which doesn't exist) and so error
      ;; propagation is unwrapped: manifold sees the child's root cause,
      ;; not an ExecutionException.
      (.whenComplete ^java.util.concurrent.CompletableFuture (.cf fil)
        (reify java.util.function.BiConsumer
          (accept [_ v e]
            (if e
              (md/error! out (if-let [c (.getCause ^Throwable e)] c e))
              (md/success! out v)))))
      out)))

(defn ->filament
  "Adapt any manifold-deferrable value `x` to a Filament. If `x` is
  already a Filament it is returned unchanged, so round-tripping
  through `->deferred` → `->filament` is a no-op."
  [x]
  (if (instance? Filament x)
    x
    (f/filament (fn [] @(md/->deferred x)))))

(defn ->deferred
  "Adapt a Filament to a `manifold.deferred/deferred`. Error propagation
  is unwrapped: the resulting deferred errors with the Filament body's
  own Throwable, not an `ExecutionException` wrapper."
  [^Filament fil]
  (md/->deferred fil))
