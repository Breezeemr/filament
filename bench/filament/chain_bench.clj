(ns filament.chain-bench
  "Criterium-based benchmark harness comparing Filament's chain/zip
   cost to Manifold's deferred baseline. Run via:

     clj -M:bench -m filament.chain-bench

   Runs all scenarios in a single JVM invocation so JIT warmup is
   shared. Uses criterium's quick-bench once for warmup, then bench
   for final numbers. Writes a markdown results table to stdout in a
   form suitable for piping to docs/bench-results/<date>.md.

   Scenarios (see docs/benchmarks.md and docs/DESIGN.md):
     1. Filament 10-step chain, single call.
     2. Filament 10-step chain, 1000 concurrent vthreads (wall time).
     3. Manifold chain' 10-step baseline on 16-thread fixed executor.
     4. Filament zip of 4 sleepers.
     5. Filament chain-10 with *capture-traces* bound to false.

   Acceptance: ratio Filament/Manifold < 2x on scenarios 1 and 4."
  (:require [criterium.core :as cc]
            [filament.deferred :as f]
            [filament.impl :as impl]
            [manifold.deferred :as md]
            [manifold.executor :as mex])
  (:import (java.time Duration Instant)
           (java.util.concurrent CountDownLatch
                                 Executors
                                 ExecutorService
                                 TimeUnit)))

;; ---------------------------------------------------------------------------
;; Scenario bodies — each returns a 0-arg fn criterium can call.
;; ---------------------------------------------------------------------------

(defn filament-chain-10 []
  @(f/chain (f/success-deferred 0)
            inc inc inc inc inc inc inc inc inc inc))

(defn filament-chain-10-no-trace []
  (binding [impl/*capture-traces* false]
    @(f/chain (f/success-deferred 0)
              inc inc inc inc inc inc inc inc inc inc)))

(defn manifold-chain-10 [pool]
  ;; manifold.deferred/chain' runs each step on whatever executor the
  ;; input deferred's callbacks resolve on; with-executor installs our
  ;; fixed pool so we compare against a real thread pool rather than
  ;; the caller thread.
  (mex/with-executor pool
    @(md/chain' (md/success-deferred 0)
                inc inc inc inc inc inc inc inc inc inc)))

(defn filament-zip-4-sleepers []
  @(apply f/zip (repeatedly 4 #(f/filament (fn [] (Thread/sleep 1))))))

(defn manifold-zip-4-sleepers [pool]
  (mex/with-executor pool
    @(apply md/zip
            (repeatedly 4
                        #(md/future-with pool (Thread/sleep 1))))))

;; ---------------------------------------------------------------------------
;; Concurrent wall-clock scenario — 1000 vthreads each running chain-10.
;; Criterium is not the right tool for this (it measures nanos) so we do
;; it the old-fashioned way: warm up once, then time N iterations.
;; ---------------------------------------------------------------------------

(defn filament-chain-10-concurrent-1000 []
  (let [n       1000
        latch   (CountDownLatch. n)
        start   (System/nanoTime)
        exec    @impl/default-executor]
    (dotimes [_ n]
      (.submit ^ExecutorService exec
               ^Runnable (fn []
                           (try (filament-chain-10)
                                (finally (.countDown latch))))))
    (.await latch)
    (/ (double (- (System/nanoTime) start)) 1e6)))

;; ---------------------------------------------------------------------------
;; Result capture + formatting.
;; ---------------------------------------------------------------------------

(defn- ns->human [ns]
  (cond
    (>= ns 1e9) (format "%.2f s"  (/ ns 1e9))
    (>= ns 1e6) (format "%.2f ms" (/ ns 1e6))
    (>= ns 1e3) (format "%.2f us" (/ ns 1e3))
    :else       (format "%.0f ns" ns)))

(defn- run-bench
  "Run criterium/benchmark and return {:mean seconds :stddev seconds}."
  [label f]
  (println (str "\n== " label " =="))
  (flush)
  (let [res (cc/benchmark* f {:samples 30
                              :target-execution-time (* 1000 1000 1000) ; 1s
                              :warmup-jit-period (* 3 1000 1000 1000)   ; 3s
                              :tail-quantile 0.025
                              :bootstrap-size 500})
        mean   (first (:mean res))
        stddev (Math/sqrt (first (:variance res)))]
    (cc/report-result res)
    {:label label :mean mean :stddev stddev}))

(defn- format-row [{:keys [label mean stddev notes]}]
  (format "| %s | %s | %s | %s |"
          label
          (if mean   (ns->human (* mean 1e9))   "—")
          (if stddev (ns->human (* stddev 1e9)) "—")
          (or notes "")))

(defn- uname []
  (try (clojure.string/trim (slurp (.getInputStream (.start (ProcessBuilder. ["uname" "-a"])))))
       (catch Throwable _ "unknown")))

(defn- cpu-model []
  (try
    (let [p (.start (ProcessBuilder. ["sh" "-c" "grep -m1 'model name' /proc/cpuinfo | cut -d: -f2-"]))]
      (clojure.string/trim (slurp (.getInputStream p))))
    (catch Throwable _ "unknown")))

(defn- java-version []
  (try
    (let [p (.start (doto (ProcessBuilder. ["java" "-version"])
                      (.redirectErrorStream true)))]
      (clojure.string/trim (slurp (.getInputStream p))))
    (catch Throwable _ (System/getProperty "java.version"))))

(defn- git-head []
  (try
    (let [p (.start (ProcessBuilder. ["git" "-C" (System/getProperty "user.dir")
                                      "rev-parse" "HEAD"]))]
      (clojure.string/trim (slurp (.getInputStream p))))
    (catch Throwable _ "unknown")))

(defn -main [& args]
  (let [quick? (some #{"--quick" "-q"} args)
        bench-fn (if quick?
                   (fn [label f]
                     (println (str "\n== " label " (quick) =="))
                     (flush)
                     (let [res (cc/quick-benchmark* f {})
                           mean   (first (:mean res))
                           stddev (Math/sqrt (first (:variance res)))]
                       (cc/report-result res)
                       {:label label :mean mean :stddev stddev}))
                   run-bench)
        pool ^ExecutorService (Executors/newFixedThreadPool 16)]
    (try
      ;; Warm up JIT on both hot paths before any real measurement.
      (println ";; Warming up Filament + Manifold JIT paths...")
      (flush)
      (cc/quick-benchmark* filament-chain-10 {})
      (cc/quick-benchmark* #(manifold-chain-10 pool) {})

      (let [fil-chain (bench-fn "Filament chain-10"         filament-chain-10)
            man-chain (bench-fn "Manifold chain'-10"        #(manifold-chain-10 pool))
            fil-notr  (bench-fn "Filament chain-10 (no trace)"
                                filament-chain-10-no-trace)
            fil-zip   (bench-fn "Filament zip-4 sleepers"   filament-zip-4-sleepers)
            man-zip   (bench-fn "Manifold zip-4 sleepers"   #(manifold-zip-4-sleepers pool))
            _         (println "\n;; Concurrent 1000-vthread chain scenario:")
            _         (flush)
            conc-ms   (do (filament-chain-10-concurrent-1000) ; warm
                          (let [samples (vec (repeatedly 5 filament-chain-10-concurrent-1000))
                                mean    (/ (reduce + samples) (count samples))]
                            (println (format ";; wall time per 1000-task batch: %.2f ms (n=5)" mean))
                            mean))
            ratio1    (when (and (:mean fil-chain) (:mean man-chain))
                        (/ (:mean fil-chain) (:mean man-chain)))
            ratio4    (when (and (:mean fil-zip)   (:mean man-zip))
                        (/ (:mean fil-zip)   (:mean man-zip)))]

        (println)
        (println (str "# Filament benchmarks — "
                      (subs (str (Instant/now)) 0 10)))
        (println)
        (println (str "JDK: "       (java-version)))
        (println (str "Hardware: "  (uname) " / " (cpu-model)))
        (println (str "Commit: "    (git-head)))
        (println (str "Mode: "      (if quick? "quick-bench" "bench")))
        (println)
        (println "| Scenario | Mean | Std dev | Notes |")
        (println "|---|---|---|---|")
        (println (format-row fil-chain))
        (println (format-row (assoc man-chain :notes "baseline")))
        (println (format "| Ratio chain (Fil/Man) | %s | | %s |"
                         (if ratio1 (format "%.2fx" ratio1) "—")
                         (if (and ratio1 (< ratio1 2.0)) "PASS (<2x)" "FAIL (>=2x)")))
        (println (format-row (assoc fil-notr
                                    :notes "isolates trace cost")))
        (println (format-row fil-zip))
        (println (format-row (assoc man-zip :notes "baseline")))
        (println (format "| Ratio zip (Fil/Man) | %s | | %s |"
                         (if ratio4 (format "%.2fx" ratio4) "—")
                         (if (and ratio4 (< ratio4 2.0)) "PASS (<2x)" "FAIL (>=2x)")))
        (println (format "| Filament chain-10 x1000 concurrent | %.2f ms | | wall time, 5 reps |"
                         conc-ms)))
      (finally
        (.shutdown pool)
        (.awaitTermination pool 5 TimeUnit/SECONDS)))
    (shutdown-agents)
    (System/exit 0)))
