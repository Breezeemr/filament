(ns filament.let-flow-test
  (:refer-clojure :exclude [loop])
  (:require [clojure.test :refer [deftest is testing]]
            [filament.core :as f :refer [let-flow loop]]))

(deftest let-flow-derefs-deferred-bindings
  (is (= 3 @(let-flow [a (f/success-deferred 1)
                       b (f/success-deferred 2)]
              (+ a b)))))

(deftest let-flow-bindings-can-reference-prior-bindings
  (is (= 2 @(let-flow [a (f/success-deferred 1)
                       b (f/success-deferred (inc a))]
              b))))

(deftest let-flow-passes-non-deferred-values-through
  (is (= 3 @(let-flow [a 1 b 2] (+ a b)))))

(deftest let-flow-runs-bindings-in-source-order
  (let [order (atom [])
        _     @(let-flow [a (do (swap! order conj :a) 1)
                          b (do (swap! order conj :b) 2)
                          c (do (swap! order conj :c) (+ a b))]
                 c)]
    (is (= [:a :b :c] @order))))

(deftest let-flow-propagates-error-unwrapped-and-catch-handles-it
  (let [d (let-flow [a (f/success-deferred 1)
                     _ (throw (ex-info "boom" {:where :binding}))]
            :unreached)
        caught @(f/catch d (fn [e] {:msg (ex-message e)
                                     :data (ex-data e)}))]
    (is (= {:msg "boom" :data {:where :binding}} caught))))

(deftest let-flow-error-from-deferred-binding-propagates
  (let [e (try @(let-flow [a (f/success-deferred 1)
                           b (f/error-deferred (ex-info "nope" {:x 1}))]
                  (+ a b))
               (catch Throwable t t))]
    (is (instance? clojure.lang.ExceptionInfo e))
    (is (= "nope" (ex-message e)))
    (is (= {:x 1} (ex-data e)))))

(def ^:dynamic *ctx* :default)

(deftest let-flow-sees-caller-dynamic-binding
  ;; Bindings established around a let-flow must be visible inside
  ;; binding expressions and the body, even though the body runs on a
  ;; vthread submitted through the executor. This is what distinguishes
  ;; a frame-conveying filament submit from a raw Runnable submit.
  (binding [*ctx* :outer]
    (is (= :outer @(let-flow [x (f/success-deferred 1)]
                     (is (= 1 x))
                     *ctx*)))
    (is (= [:outer :outer] @(let-flow [a (f/success-deferred *ctx*)
                                        b (f/filament (fn [] *ctx*))]
                              [a b])))))

(deftest let-flow-binding-throw-has-clean-trace
  (let [e      (try @(let-flow [a (f/success-deferred 1)
                                _ (throw (ex-info "binding-throw" {:k a}))]
                       :unreached)
                    (catch Throwable t t))
        frames (mapv #(.getClassName ^StackTraceElement %) (.getStackTrace e))]
    (is (= "binding-throw" (ex-message e)))
    (is (= {:k 1} (ex-data e)))
    (is (not-any? #(re-find #"ThreadPoolExecutor|ForkJoinWorkerThread" %) frames)
        (str "executor frame leaked into trace; frames=" frames))
    (is (some #(re-find #"filament\.let_flow_test" %) frames)
        (str "caller frame missing from binding-throw trace; frames=" frames))))

(deftest loop-runs-on-a-single-thread
  (let [threads (atom #{})
        result  @(loop [i 0]
                   (swap! threads conj (Thread/currentThread))
                   (if (< i 10)
                     (recur (inc i))
                     i))]
    (is (= 10 result))
    (is (= 1 (count @threads)))))

(deftest loop-returns-final-value
  (is (= 55 @(loop [i 0 acc 0]
               (if (> i 10)
                 acc
                 (recur (inc i) (+ acc i)))))))
