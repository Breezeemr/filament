(ns filament.fork-test
  (:require [clojure.test :refer [deftest is testing]]
            [filament.core :as f])
  (:import (java.util.concurrent CancellationException TimeoutException)
           (java.util.concurrent.atomic AtomicBoolean)))

;; ---------------------------------------------------------------------------
;; zip
;; ---------------------------------------------------------------------------

(deftest zip-collects-results-in-order
  (is (= [1 2 3]
         @(f/zip (f/success-deferred 1)
                 (f/success-deferred 2)
                 (f/success-deferred 3)))))

(deftest zip-runs-children-in-parallel
  (let [start (System/nanoTime)
        _     @(f/zip (f/filament (fn [] (Thread/sleep 100) :a))
                      (f/filament (fn [] (Thread/sleep 100) :b))
                      (f/filament (fn [] (Thread/sleep 100) :c)))
        dt    (/ (- (System/nanoTime) start) 1e6)]
    ;; Serial would be ~300ms; parallel should be well under 250ms.
    (is (< dt 250.0) (str "elapsed=" dt "ms"))))

(deftest zip-failure-throws-unwrapped-cause
  (let [e (try @(f/zip (f/success-deferred 1)
                       (f/filament (fn [] (throw (ex-info "child-boom" {:k 1})))))
               (catch Throwable t t))]
    (is (instance? clojure.lang.ExceptionInfo e))
    (is (= "child-boom" (ex-message e)))
    (is (= {:k 1} (ex-data e)))))

(deftest zip-failure-has-exactly-one-fork-site-trace
  (let [e (try @(f/zip (f/success-deferred 1)
                       (f/filament (fn [] (throw (ex-info "child-boom" {})))))
               (catch Throwable t t))
        supp (vec (.getSuppressed e))
        marker-frames (filter (fn [^Throwable s]
                                (let [m (.getMessage s)]
                                  (and m (.contains ^String m "forked from"))))
                              supp)]
    (is (= 1 (count marker-frames))
        (str "expected exactly one fork-site trace, got "
             (count marker-frames) " of " (count supp) " suppressed"))))

(deftest zip-empty-returns-empty-vector
  (is (= [] @(f/zip))))

;; ---------------------------------------------------------------------------
;; alt
;; ---------------------------------------------------------------------------

(deftest alt-returns-first-successful-result
  (let [winner @(f/alt (f/filament (fn [] (Thread/sleep 500) :slow))
                       (f/success-deferred :fast))]
    (is (= :fast winner))))

(deftest alt-ignores-failing-children-if-some-succeed
  (is (= :ok
         @(f/alt (f/filament (fn [] (throw (ex-info "boom1" {}))))
                 (f/success-deferred :ok)))))

(deftest alt-all-failing-throws
  (let [e (try @(f/alt (f/filament (fn [] (throw (ex-info "a" {}))))
                       (f/filament (fn [] (throw (ex-info "b" {})))))
               (catch Throwable t t))]
    (is (instance? Throwable e))))

;; ---------------------------------------------------------------------------
;; timeout!
;; ---------------------------------------------------------------------------

(deftest timeout!-throws-on-slow
  (let [e (try @(f/timeout! (f/filament (fn [] (Thread/sleep 2000) :done))
                            50)
               (catch Throwable t t))]
    (is (instance? TimeoutException e))))

(deftest timeout!-passes-through-fast-result
  (is (= :done @(f/timeout! (f/success-deferred :done) 1000))))

(deftest timeout!-fallback-on-slow
  (is (= :fallback
         @(f/timeout! (f/filament (fn [] (Thread/sleep 2000) :done))
                      50 :fallback))))

(deftest timeout!-fallback-not-used-on-fast-success
  (is (= :done
         @(f/timeout! (f/success-deferred :done) 1000 :fallback))))

;; ---------------------------------------------------------------------------
;; cancel!
;; ---------------------------------------------------------------------------

(deftest cancel!-interrupts-running-filament
  (let [started (promise)
        d (f/filament (fn []
                        (deliver started :ok)
                        (Thread/sleep 10000)
                        :done))]
    @started
    ;; Let the runner reference be visible.
    (Thread/sleep 20)
    (let [start (System/nanoTime)]
      (f/cancel! d)
      (let [e (try @d (catch Throwable t t))
            dt (/ (- (System/nanoTime) start) 1e6)]
        (is (instance? CancellationException e))
        (is (< dt 500.0) (str "cancel took " dt "ms"))))))

(deftest cancel!-on-zip-interrupts-both-children
  (let [c1-interrupted (AtomicBoolean. false)
        c2-interrupted (AtomicBoolean. false)
        c1-started     (promise)
        c2-started     (promise)
        child1 (f/filament (fn []
                             (deliver c1-started :ok)
                             (try (Thread/sleep 10000) :done
                                  (catch InterruptedException _
                                    (.set c1-interrupted true)
                                    (throw (ex-info "child1 interrupted" {}))))))
        child2 (f/filament (fn []
                             (deliver c2-started :ok)
                             (try (Thread/sleep 10000) :done
                                  (catch InterruptedException _
                                    (.set c2-interrupted true)
                                    (throw (ex-info "child2 interrupted" {}))))))
        zipped (f/zip child1 child2)]
    @c1-started @c2-started
    (Thread/sleep 30)
    (f/cancel! zipped)
    ;; Drain
    (try @zipped (catch Throwable _))
    ;; Give children a moment to observe the interrupt.
    (let [deadline (+ (System/currentTimeMillis) 500)]
      (while (and (or (not (.get c1-interrupted))
                      (not (.get c2-interrupted)))
                  (< (System/currentTimeMillis) deadline))
        (Thread/sleep 10)))
    (is (.get c1-interrupted) "child1 did not see InterruptedException")
    (is (.get c2-interrupted) "child2 did not see InterruptedException")))
