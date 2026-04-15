(ns filament.time-test
  (:require [clojure.test :refer [deftest is testing]]
            [filament.deferred :as d]
            [filament.time :as t])
  (:import (java.util.concurrent CancellationException)))

(deftest unit-conversions
  (is (= 1.0 (t/nanoseconds 1e6)))
  (is (= 1.0 (t/microseconds 1e3)))
  (is (= 1.0 (t/milliseconds 1.0)))
  (is (= 1000.0 (t/seconds 1)))
  (is (= 60000.0 (t/minutes 1)))
  (is (= 3600000.0 (t/hours 1)))
  (is (= 86400000.0 (t/days 1)))
  (is (= 1000.0 (t/hz 1))))

(deftest format-duration-test
  (is (= "0s" (t/format-duration 0)))
  (is (= "1s" (t/format-duration 1000)))
  (is (= "1m" (t/format-duration 60000)))
  (is (= "1d 2h 3m 4s"
         (t/format-duration (+ (t/days 1) (t/hours 2)
                               (t/minutes 3) (t/seconds 4))))))

(deftest floor-and-add
  (is (= 1000 (t/floor 1999 :second)))
  (is (= 60000 (t/floor (long (t/seconds 61)) :minute)))
  (is (= (long (+ 1000 (t/days 1))) (t/add 1000 1 :day))))

(deftest in-resolves
  (is (= 42 @(t/in 1 (fn [] 42)))))

(deftest in-unwraps-deferred
  (is (= :v @(t/in 1 (fn [] (d/success-deferred :v))))))

(deftest in-propagates-throw
  (is (thrown-with-msg? Exception #"boom"
        @(t/in 1 (fn [] (throw (ex-info "boom" {})))))))

(deftest in-cancellable
  (let [d (t/in 60000 (fn [] (Thread/sleep 60000) :nope))]
    (d/cancel! d)
    (is (thrown? CancellationException @d))))

(deftest at-runs-when-past
  (is (= :ok @(t/at (- (System/currentTimeMillis) 100) (fn [] :ok)))))

(deftest every-cancellable
  (let [cnt (atom 0)
        d   (t/every 1000 5 (fn [] (swap! cnt inc)))]
    (Thread/sleep 60)
    (d/cancel! d)
    (is (= 1 @cnt))
    (is (thrown? CancellationException @d))))

(deftest every-auto-cancels-on-throw
  (let [cnt (atom 0)
        d   (t/every 5 (fn []
                         (swap! cnt inc)
                         (throw (ex-info "boom" {}))))]
    (is (thrown-with-msg? Exception #"boom" @d))
    (let [seen @cnt]
      (Thread/sleep 50)
      (is (= seen @cnt) "schedule stopped after first throw"))))

(deftest mock-clock-now-and-advance
  (let [c (t/mock-clock)]
    (is (= 0.0 (t/now c)))
    (t/advance c 100)
    (is (= 100.0 (t/now c)))))

(deftest mock-clock-in
  (let [c   (t/mock-clock)
        ran (atom false)]
    (t/with-clock c
      (let [d (t/in 100 (fn [] (reset! ran true) :v))]
        (t/advance c 50)
        (is (false? @ran))
        (t/advance c 60)
        (is (= :v @d))
        (is (true? @ran))))))

(deftest mock-clock-every-fires-and-cancels
  (let [c   (t/mock-clock)
        cnt (atom 0)]
    (t/with-clock c
      (let [d (t/every 10 (fn [] (swap! cnt inc)))]
        ;; ticks at 0, 10, 20, 30 — vthread bodies are submitted inline
        (t/advance c 30)
        ;; bodies are async on vthreads; give them a moment to land
        (Thread/sleep 50)
        (is (= 4 @cnt))
        (d/cancel! d)
        (let [seen @cnt]
          (t/advance c 100)
          (Thread/sleep 30)
          (is (= seen @cnt)))))))

(deftest with-clock-rebinds
  (let [c (t/mock-clock)]
    (is (identical? c (t/with-clock c t/*clock*)))))
