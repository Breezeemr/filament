(ns filament.fork-bench
  "Fork-path micro-benchmarks: zip / alt / timeout! on Filament
   compared to Manifold's equivalents. Run via:

     clj -M:bench -m filament.fork-bench

   Intentionally small: the main report is produced by
   filament.chain-bench. This namespace exists so you can iterate on
   fork-specific regressions without sitting through the full chain
   report. Uses criterium's quick-bench for everything."
  (:require [criterium.core :as cc]
            [filament.core :as f]
            [manifold.deferred :as md]
            [manifold.executor :as mex])
  (:import (java.util.concurrent Executors ExecutorService TimeUnit)))

(defn- fil-zip-4 []
  @(apply f/zip (repeatedly 4 #(f/filament (fn [] 1)))))

(defn- fil-alt-4 []
  @(apply f/alt
          (f/success-deferred :fast)
          (repeatedly 3 #(f/filament (fn [] (Thread/sleep 5) :slow)))))

(defn- fil-timeout-ok []
  @(f/timeout! (f/success-deferred :ok) 100 :fallback))

(defn- man-zip-4 [pool]
  (mex/with-executor pool
    @(apply md/zip (repeatedly 4 #(md/future-with pool 1)))))

(defn- man-alt-4 [pool]
  (mex/with-executor pool
    @(md/alt (md/success-deferred :fast)
             (md/future-with pool (do (Thread/sleep 5) :slow))
             (md/future-with pool (do (Thread/sleep 5) :slow))
             (md/future-with pool (do (Thread/sleep 5) :slow)))))

(defn- run [label f]
  (println (str "\n== " label " =="))
  (flush)
  (cc/quick-bench (f)))

(defn -main [& _]
  (let [pool ^ExecutorService (Executors/newFixedThreadPool 16)]
    (try
      (run "Filament zip-4"          fil-zip-4)
      (run "Manifold zip-4"          #(man-zip-4 pool))
      (run "Filament alt-4"          fil-alt-4)
      (run "Manifold alt-4"          #(man-alt-4 pool))
      (run "Filament timeout! ok"    fil-timeout-ok)
      (finally
        (.shutdown pool)
        (.awaitTermination pool 5 TimeUnit/SECONDS))))
  (shutdown-agents)
  (System/exit 0))
