(ns filament.chain-test
  (:require [clojure.test :refer [deftest is testing]]
            [filament.deferred :as f]))

(deftest chain-sync-steps
  (is (= 4 @(f/chain (f/success-deferred 1) inc inc inc))))

(deftest chain-through-deferred-returning-step
  (is (= 3 @(f/chain (f/success-deferred 1)
                     inc
                     #(f/success-deferred (inc %))))))

(deftest chain-no-steps
  (is (= 1 @(f/chain (f/success-deferred 1)))))

(deftest chain-derefs-initial-deferred
  (is (= 2 @(f/chain (f/filament (fn [] 1)) inc))))

(deftest chain-error-propagates-original-throwable
  (let [data (try @(f/chain (f/success-deferred 1)
                            (fn [_] (throw (ex-info "boom" {:step 2}))))
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= {:step 2} data))))

(deftest chain-error-in-later-step
  (let [e (try @(f/chain (f/success-deferred 0)
                         inc
                         inc
                         (fn [_] (throw (ex-info "nope" {}))))
               (catch Throwable t t))]
    (is (= "nope" (ex-message e)))))

(deftest catch-intercepts-error
  (is (= :handled
         @(f/catch (f/chain (f/success-deferred 1)
                            (fn [_] (throw (ex-info "boom" {}))))
                   (fn [_e] :handled)))))

(deftest catch-passes-value-through-on-success
  (is (= 5 @(f/catch (f/success-deferred 5) (fn [_] :unused)))))

(deftest catch-class-filter-matches
  (is (= :caught
         @(f/catch (f/chain (f/success-deferred 1)
                            (fn [_] (throw (ex-info "boom" {}))))
                   clojure.lang.ExceptionInfo
                   (fn [_e] :caught)))))

(deftest catch-class-filter-rethrows-unmatched
  (let [e (try @(f/catch (f/chain (f/success-deferred 1)
                                  (fn [_] (throw (ex-info "boom" {:x 1}))))
                         IllegalArgumentException
                         (fn [_e] :not-reached))
               (catch Throwable t t))]
    (is (instance? clojure.lang.ExceptionInfo e))
    (is (= "boom" (ex-message e)))
    (is (= {:x 1} (ex-data e)))))

(deftest catch-handler-can-return-deferred
  (is (= :from-deferred
         @(f/catch (f/chain (f/success-deferred 1)
                            (fn [_] (throw (ex-info "boom" {}))))
                   (fn [_] (f/success-deferred :from-deferred))))))

(deftest finally-runs-on-success
  (let [ran (atom false)]
    (is (= 7 @(f/finally (f/success-deferred 7)
                         (fn [] (reset! ran true)))))
    (is (true? @ran))))

(deftest finally-runs-on-failure-and-propagates-error
  (let [ran (atom false)
        e   (try @(f/finally (f/chain (f/success-deferred 1)
                                      (fn [_] (throw (ex-info "boom" {}))))
                             (fn [] (reset! ran true)))
                 (catch Throwable t t))]
    (is (true? @ran))
    (is (= "boom" (ex-message e)))))
