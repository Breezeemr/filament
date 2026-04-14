(ns filament.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [filament.core :as f]
            [filament.impl :as impl])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(deftest filament-deref-returns-value
  (is (= 42 @(f/filament (fn [] 42)))))

(deftest success-deferred-derefs
  (is (= :ok @(f/success-deferred :ok))))

(deftest error-deferred-throws-cause-not-execution-exception
  (let [e (try @(f/error-deferred (ex-info "boom" {}))
               (catch Throwable t t))]
    (is (instance? clojure.lang.ExceptionInfo e))
    (is (= "boom" (ex-message e)))))

(deftest realized?-reflects-cf-state
  (let [d (f/filament (fn [] (Thread/sleep 50) 1))]
    (is (false? (realized? d)))
    (Thread/sleep 200)
    (is (true? (realized? d)))
    (is (= 1 @d))))

(deftest timed-deref-returns-timeout-val
  (let [d (f/filament (fn [] (Thread/sleep 500) 1))]
    (is (= :timeout (deref d 10 :timeout)))))

(defn- start-vthread ^Thread [^Runnable r]
  (Thread/startVirtualThread r))

(deftest deferred-success!-roundtrip-cross-thread
  (let [d      (f/deferred)
        latch  (CountDownLatch. 1)
        result (atom nil)]
    (start-vthread (fn []
                     (reset! result @d)
                     (.countDown latch)))
    (Thread/sleep 20)
    (is (true? (f/success! d :delivered)))
    (is (.await latch 2 TimeUnit/SECONDS))
    (is (= :delivered @result))))

(deftest error!-delivers-to-concurrent-deref
  (let [d      (f/deferred)
        result (promise)]
    (start-vthread (fn []
                     (try @d
                          (catch Throwable e
                            (deliver result e)))))
    (Thread/sleep 20)
    (f/error! d (ex-info "nope" {}))
    (let [e (deref result 2000 :fail)]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (= "nope" (ex-message e))))))

(deftest add-suppressed-once-is-idempotent
  (let [target (Throwable. "target")
        synth  (fn [] (Throwable. (str "filament: deref site " @#'impl/marker)))]
    (impl/add-suppressed-once target (synth))
    (impl/add-suppressed-once target (synth))
    (impl/add-suppressed-once target (synth))
    (is (= 1 (count (.getSuppressed target))))))

(deftest capture-traces-false-does-not-add-suppressed
  (binding [impl/*capture-traces* false]
    (let [d (f/filament (fn [] (Thread/sleep 20) (throw (ex-info "quiet" {}))))
          e (try @d (catch Throwable t t))]
      (is (= "quiet" (ex-message e)))
      (is (zero? (count (.getSuppressed e)))))))

(deftest in-resolves-after-delay
  (let [start (System/nanoTime)
        v     @(f/in 30 (fn [] :tick))
        elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
    (is (= :tick v))
    (is (>= elapsed-ms 25))))

(deftest in-error-propagates-with-vthread-frames
  (let [d (f/in 10 (fn [] (throw (ex-info "boom-in" {}))))
        e (try @d ::no-throw (catch Throwable t t))]
    (is (instance? clojure.lang.ExceptionInfo e))
    (is (= "boom-in" (ex-message e)))
    ;; The throw ran on a vthread submitted by `in`, so the top frame is
    ;; the user fn itself — no manifold timer-thread frames in between.
    (let [frames (map str (.getStackTrace e))]
      (is (some #(re-find #"filament.core_test" %) frames)))))

(deftest in-cancel-before-tick-prevents-run
  (let [ran (atom false)
        d   (f/in 200 (fn [] (reset! ran true) :late))]
    (f/cancel! d)
    (Thread/sleep 300)
    (is (false? @ran))
    (is (thrown? java.util.concurrent.CancellationException @d))))

(deftest every-ticks-repeatedly-and-cancels
  (let [hits (atom 0)
        d    (f/every 20 (fn [] (swap! hits inc)))]
    (Thread/sleep 120)
    (f/cancel! d)
    ;; Let any tick already running on a vthread finish before we snapshot.
    (Thread/sleep 50)
    (let [seen @hits]
      (is (>= seen 3))
      (Thread/sleep 80)
      (is (= seen @hits)))))
