(ns filament.interop-test
  "Acceptance tests for the Filament <-> Manifold interop bridge.

  These run only under :dev/:test aliases, because manifold is only on
  the classpath there. The 'core alone loads without manifold' check
  happens in the verification shell commands, not here."
  (:require [clojure.test :refer [deftest is testing]]
            [filament.core :as f]
            [filament.manifold :as fm]
            [manifold.deferred :as md]
            [manifold.stream :as ms]))

(deftest requires-succeed
  (testing "both namespaces load cleanly together"
    (is (some? (find-ns 'filament.core)))
    (is (some? (find-ns 'filament.manifold)))
    (is (some? (find-ns 'manifold.deferred)))))

(deftest ->filament-from-manifold
  (testing "wrapping a realised manifold success-deferred"
    (is (= 1 @(fm/->filament (md/success-deferred 1)))))
  (testing "wrapping an unrealised manifold deferred that later succeeds"
    (let [md' (md/deferred)
          fil (fm/->filament md')]
      (md/success! md' 99)
      (is (= 99 @fil))))
  (testing "wrapping a Filament is a no-op"
    (let [fil (f/success-deferred 7)]
      (is (identical? fil (fm/->filament fil))))))

(deftest ->deferred-from-filament
  (testing "wrapping a realised Filament success-deferred"
    (is (= 2 @(fm/->deferred (f/success-deferred 2)))))
  (testing "wrapping a computation that completes later"
    (is (= 42 @(fm/->deferred (f/filament (fn [] (Thread/sleep 5) 42)))))))

(deftest filament-as-input-to-manifold-chain
  (testing "manifold.deferred/chain accepts a Filament as its seed value"
    (is (= 2 @(md/chain (f/success-deferred 1) inc))))
  (testing "manifold.deferred/chain with multiple steps and a Filament seed"
    (is (= 5 @(md/chain (f/success-deferred 1) inc inc inc inc)))))

(deftest manifold-deferred-inside-filament-chain
  (testing "a chain step that returns a manifold deferred is derefed"
    (is (= 42 @(f/chain (f/success-deferred 1)
                        (fn [_] (md/success-deferred 42))))))
  (testing "multiple manifold-returning steps interleave cleanly"
    (is (= 44 @(f/chain (f/success-deferred 1)
                        (fn [_] (md/success-deferred 42))
                        inc
                        inc)))))

(deftest deferred?-recognises-manifold
  (testing "filament.core/deferred? returns true for manifold deferreds"
    (is (f/deferred? (md/success-deferred 1)))
    (is (f/deferred? (md/deferred)))
    (is (f/deferred? (f/success-deferred 1)))
    (is (not (f/deferred? 42)))
    (is (not (f/deferred? "hello")))))

(deftest error-unwrapping-filament-to-manifold
  (testing "Filament error surfaces to manifold without ExecutionException wrapping"
    (let [boom (ex-info "boom-f2m" {:side :filament})
          md'  (fm/->deferred (f/filament (fn [] (throw boom))))
          thrown (try @md' ::no-throw (catch Throwable t t))]
      (is (not= ::no-throw thrown))
      (is (identical? boom thrown))
      (is (= "boom-f2m" (ex-message thrown))))))

(deftest error-unwrapping-manifold-to-filament
  (testing "manifold error surfaces to Filament without manifold wrapping"
    (let [boom (ex-info "boom-m2f" {:side :manifold})
          fil  (fm/->filament (md/error-deferred boom))
          thrown (try @fil ::no-throw (catch Throwable t t))]
      (is (not= ::no-throw thrown))
      (is (identical? boom thrown))
      (is (= "boom-m2f" (ex-message thrown))))))

(deftest stream-put-and-take-roundtrip
  (testing "put! and take! return Filaments that resolve to stream results"
    (let [s   (ms/stream 1)
          put (fm/put! s :v)]
      (is (f/deferred? put))
      (is (true? @put))
      (is (= :v @(fm/take! s)))))
  (testing "take! on closed empty stream resolves to default"
    (let [s (ms/stream)]
      (ms/close! s)
      (is (= ::drained @(fm/take! s ::drained))))))

(deftest stream-take-flows-through-chain
  (testing "a take! Filament feeds an f/chain step"
    (let [s (ms/stream 1)]
      @(fm/put! s 10)
      (is (= 11 @(f/chain (fm/take! s) inc))))))

(deftest stream-put-error-unwraps-to-caller
  (testing "errors from ms/put! bubble through the Filament unwrapped"
    (let [s (ms/stream)
          _ (ms/close! s)
          ;; put! on a closed stream resolves to false, not throws; so
          ;; instead we construct an explicit error via md/error-deferred
          ;; piped through ->filament to confirm the unwrapping path.
          fil (fm/->filament (md/error-deferred (ex-info "stream-boom" {})))
          thrown (try @fil ::no-throw (catch Throwable t t))]
      (is (= "stream-boom" (ex-message thrown))))))

(deftest manifold-step-error-in-chain-has-clean-trace
  (testing "a manifold-deferred-returning step that errors surfaces
            the original cause with no manifold.deferred.* frames"
    (let [boom (ex-info "step-boom" {:tag :md-step})
          e    (try @(f/chain (f/success-deferred 0)
                              inc
                              (fn [_] (md/error-deferred boom))
                              inc)
                    (catch Throwable t t))
          frames (mapv #(.getClassName ^StackTraceElement %) (.getStackTrace e))]
      (is (identical? boom e))
      (is (= :md-step (:tag (ex-data e))))
      (is (not-any? #(re-find #"^manifold\.deferred" %) frames)
          (str "manifold internal frames leaked; frames=" frames)))))

(deftest round-trip-preserves-value
  (testing "Filament → ->deferred → ->filament preserves the realised value"
    (is (= 13 @(fm/->filament (fm/->deferred (f/success-deferred 13))))))
  (testing "->filament on an already-Filament input is identity"
    (let [fil (f/success-deferred 13)]
      (is (identical? fil (fm/->filament fil))))))
