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
