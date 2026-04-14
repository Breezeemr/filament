(ns filament.trace-test
  (:require [clojure.test :refer [deftest is]]
            [filament.core :as f]
            [filament.impl :as impl]))

(defn- frame-classes [^Throwable t]
  (mapv #(.getClassName ^StackTraceElement %) (.getStackTrace t)))

(defn- no-executor-noise? [frames]
  (not-any? #(re-find #"ThreadPoolExecutor|ForkJoinWorkerThread|ForkJoinPool"
                      %)
            frames))

(defn- suppressed-with [^Throwable t needle]
  (filter (fn [^Throwable s]
            (let [m (.getMessage s)]
              (and m (.contains ^String m ^String needle))))
          (vec (.getSuppressed t))))

(deftest chain-trace-linear
  (let [e      (try @(f/chain (f/success-deferred 0)
                              inc inc
                              (fn [_] (throw (ex-info "boom" {:tag :chain}))))
                    (catch Throwable t t))
        frames (frame-classes e)]
    (is (= "boom" (ex-message e)))
    (is (= :chain (:tag (ex-data e))))
    (is (no-executor-noise? frames)
        (str "executor frame leaked into trace; frames=" frames))
    (is (some #(re-find #"filament\.trace_test" %) frames)
        (str "caller frame missing; frames=" frames))))

(deftest zip-has-forked-from-suppressed
  (let [e     (try @(f/zip (f/success-deferred 1)
                           (f/filament (fn [] (throw (ex-info "child" {})))))
                   (catch Throwable t t))
        marks (vec (suppressed-with e "forked from"))]
    (is (= "child" (ex-message e)))
    (is (= 1 (count marks))
        (str "expected exactly one forked-from suppressed trace, got " (count marks)))
    (let [^Throwable mark (first marks)]
      (is (re-find #"__filament_marker__" (.getMessage mark)))
      (is (some #(re-find #"filament\.trace_test" %)
                (frame-classes mark))
          (str "fork-site trace does not include test ns; frames="
               (frame-classes mark))))))

(deftest nested-chain-inside-zip-attributes-correctly
  ;; A chain-inside-zip should surface the innermost throw as the root
  ;; cause (unwrapped past scope wrappers) and attach exactly one
  ;; forked-from marker at the zip site.
  (let [e     (try @(f/zip (f/success-deferred :ok)
                           (f/chain (f/success-deferred 0)
                                    inc
                                    (fn [_]
                                      (throw (ex-info "inner"
                                                      {:tag :nested})))))
                   (catch Throwable t t))
        marks (vec (suppressed-with e "forked from"))]
    (is (= "inner" (ex-message e)))
    (is (= :nested (:tag (ex-data e))))
    (is (no-executor-noise? (frame-classes e))
        (str "executor frame leaked; frames=" (frame-classes e)))
    (is (= 1 (count marks))
        (str "expected exactly one forked-from marker, got " (count marks)))))

(deftest deep-chain-failure-trace-is-clean
  ;; 10k-step chain with an error at the last step. The caller must see
  ;; the original ex-info unwrapped, with no executor noise in the
  ;; trace, and a bounded number of suppressed entries (not one per
  ;; step). This defends filament.core's "bounded, inline" chain claim.
  (let [steps  (concat (repeat 10000 inc)
                       [(fn [_] (throw (ex-info "deep" {:tag :deep})))])
        e      (try @(apply f/chain (f/success-deferred 0) steps)
                    (catch Throwable t t))
        frames (frame-classes e)]
    (is (= "deep" (ex-message e)))
    (is (= :deep (:tag (ex-data e))))
    (is (no-executor-noise? frames)
        (str "executor frame leaked into deep-chain trace; frames=" frames))
    (is (<= (count (.getSuppressed e)) 3)
        (str "too many suppressed entries on a deep chain: "
             (count (.getSuppressed e))))))

(deftest capture-traces-off-suppresses-nothing
  (binding [impl/*capture-traces* false]
    (let [e (try @(f/zip (f/filament (fn [] (throw (ex-info "x" {})))))
                 (catch Throwable t t))]
      (is (= "x" (ex-message e)))
      (is (empty? (suppressed-with e "forked from"))
          "no forked-from trace should be attached when *capture-traces* is false"))))
