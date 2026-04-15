(ns filament.bench.hot-path
  "Tiny workload that exercises every public Filament entry point once
   on a modest payload. Used by the pinning diagnostic test: run this in
   a sub-JVM with -Djdk.tracePinnedThreads=full and assert stderr does
   not contain 'Thread is pinned'. Kept deliberately cheap (<500ms)."
  (:require [filament.deferred :as f]))

(defn run! []
  ;; chain
  (let [r @(f/chain (f/success-deferred 0)
                    inc inc inc inc inc)]
    (assert (= 5 r)))
  ;; let-flow
  (let [r @(f/let-flow [a (f/success-deferred 1)
                        b (f/success-deferred 2)
                        c (f/filament (fn [] (+ a b)))]
             (* c 2))]
    (assert (= 6 r)))
  ;; loop
  (let [r @(f/loop [i 0]
             (if (< i 50) (recur (inc i)) i))]
    (assert (= 50 r)))
  ;; zip
  (let [r @(f/zip (f/success-deferred 1)
                  (f/filament (fn [] 2))
                  (f/chain (f/success-deferred 0) inc inc inc))]
    (assert (= [1 2 3] r)))
  ;; alt
  (let [r @(f/alt (f/filament (fn [] (Thread/sleep 50) :slow))
                  (f/success-deferred :fast))]
    (assert (= :fast r)))
  ;; timeout! with fallback
  (let [r @(f/timeout! (f/filament (fn [] (Thread/sleep 200) :late))
                       20 :fallback)]
    (assert (= :fallback r)))
  ;; catch
  (let [r @(f/catch (f/filament (fn [] (throw (ex-info "x" {}))))
                    (fn [_] :handled))]
    (assert (= :handled r)))
  ;; finally
  (let [hit (volatile! false)
        r   @(f/finally (f/success-deferred :ok)
                        (fn [] (vreset! hit true)))]
    (assert (= :ok r))
    (assert @hit))
  ;; cancel!
  (let [d (f/filament (fn [] (Thread/sleep 500) :never))]
    (f/cancel! d)
    (try @d (catch Throwable _)))
  :done)

(defn -main [& _]
  (run!)
  (println "hot-path ok")
  (System/exit 0))
