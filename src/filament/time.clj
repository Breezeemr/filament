(ns filament.time
  "Manifold-compatible timing primitives, backed by virtual threads.

  Near drop-in replacement for `manifold.time`: unit converters,
  calendar helpers, `in`/`every`/`at`, and a pluggable `IClock` (with a
  deterministic `mock-clock` for tests).

  The default clock is backed by one daemon-threaded
  ScheduledExecutorService. Each tick submits the user body through
  `filament.impl/filament`, so the body runs on a virtual thread and a
  throw carries the vthread's frames — exactly the trace shape Filament
  is built for. The scheduler thread itself never runs user code.

  Differences from `manifold.time`:

    * `in` and `at` return a `Filament` rather than a manifold deferred.
      Both are derefable async values resolving to `f`'s result (a
      deferred returned by `f` is unwrapped), so usage is the same; the
      concrete type differs.

    * `every` returns a `Filament`, not a zero-arg cancel function.
      Cancel via `(filament.deferred/cancel! d)` instead of `(stop)`.
      As a bonus you can `@d` to observe a tick throw — manifold's
      cancel-fn shape can't surface the error to the caller. The
      auto-deschedule-on-throw contract itself is unchanged.

    * `mock-clock` fires scheduled Runnables synchronously during
      `advance`, but `in`/`every` submit the user body to a virtual
      thread, so observing effects after `advance` may require a brief
      wait (or a `@(in ...)` deref) for the vthread to land. Manifold's
      mock clock runs user fns inline on the calling thread."
  (:require [clojure.string :as str]
            [filament.deferred :as d]
            [filament.impl :as impl])
  (:import (filament.impl Filament)
           (java.util Calendar TimeZone)
           (java.util.concurrent CompletableFuture
                                 ScheduledExecutorService
                                 ScheduledThreadPoolExecutor
                                 ThreadFactory
                                 TimeUnit)
           (java.util.concurrent.atomic AtomicReference)
           (java.util.function BiConsumer)))

;; ---------------------------------------------------------------------------
;; Unit converters
;; ---------------------------------------------------------------------------

(defn nanoseconds  "Converts nanoseconds -> milliseconds"  [^double n] (/ n 1e6))
(defn microseconds "Converts microseconds -> milliseconds" [^double n] (/ n 1e3))
(defn milliseconds "Converts milliseconds -> milliseconds" [^double n] n)
(defn seconds      "Converts seconds -> milliseconds"      [^double n] (* n 1e3))
(defn minutes      "Converts minutes -> milliseconds"      [^double n] (* n 6e4))
(defn hours        "Converts hours -> milliseconds"        [^double n] (* n 36e5))
(defn days         "Converts days -> milliseconds"         [^double n] (* n 864e5))
(defn hz           "Converts frequency -> period in milliseconds" [^double n] (/ 1e3 n))

(let [intervals (partition 2 ["d" (days 1)
                              "h" (hours 1)
                              "m" (minutes 1)
                              "s" (seconds 1)])]
  (defn format-duration
    "Takes a duration in milliseconds, returns a formatted string
     describing the interval, e.g. '5d 3h 1m'."
    [^double n]
    (clojure.core/loop [s "", n n, intervals intervals]
      (if (empty? intervals)
        (if (empty? s) "0s" (str/trim s))
        (let [[desc ^double v] (first intervals)]
          (if (>= n v)
            (recur (str s (int (/ n v)) desc " ")
                   (rem n v)
                   (rest intervals))
            (recur s n (rest intervals))))))))

(let [sorted-units         [:millisecond Calendar/MILLISECOND
                            :second      Calendar/SECOND
                            :minute      Calendar/MINUTE
                            :hour        Calendar/HOUR
                            :day         Calendar/DAY_OF_YEAR
                            :week        Calendar/WEEK_OF_MONTH
                            :month       Calendar/MONTH]
      unit->cal            (apply hash-map sorted-units)
      units                (->> sorted-units (partition 2) (map first))
      unit->cleared-fields (zipmap units
                                   (map #(->> (take % units) (map unit->cal))
                                        (range (count units))))]

  (defn floor
    "Round `timestamp` down to the nearest even multiple of `unit`.

       (floor 1001 :second) => 1000
       (floor (seconds 61) :minute) => 60000"
    [timestamp unit]
    (assert (contains? unit->cal unit))
    (let [^Calendar cal (doto (Calendar/getInstance (TimeZone/getTimeZone "UTC"))
                          (.setTimeInMillis (long timestamp)))]
      (doseq [field (unit->cleared-fields unit)]
        (.set cal field 0))
      (.getTimeInMillis cal)))

  (defn add
    "Add `value` multiples of `unit` to `timestamp`."
    [timestamp value unit]
    (assert (contains? unit->cal unit))
    (let [^Calendar cal (doto (Calendar/getInstance (TimeZone/getTimeZone "UTC"))
                          (.setTimeInMillis (long timestamp)))]
      (.add cal (unit->cal unit) (int value))
      (.getTimeInMillis cal))))

;; ---------------------------------------------------------------------------
;; IClock — pluggable scheduling, identical contract to manifold.time/IClock.
;;
;; A clock schedules raw Runnables. `in`/`every` below wrap user fns to
;; launch on a virtual thread and then delegate to the active clock, so the
;; clock implementation never runs user code on its own threads.
;; ---------------------------------------------------------------------------

(defprotocol IClock
  (clock-in    [clock interval-ms f]
    "Schedule `f` to run once after `interval-ms`. Returns a 0-arg cancel fn.")
  (clock-every [clock delay-ms period-ms f]
    "Schedule `f` to run every `period-ms` after `delay-ms`. Returns cancel fn."))

(defprotocol IMockClock
  (now [clock] "Returns the current mock time in ms.")
  (advance [clock time]
    "Advances the mock clock by `time` ms, firing each scheduled event in
    order. For a periodic event scheduled with `(every 1 ...)`, advancing
    by 5 fires it 6 times (initial tick + five steps), matching
    `manifold.time/mock-clock`."))

(defn scheduled-executor->clock
  "Wrap a `ScheduledExecutorService` as an `IClock`."
  [^ScheduledExecutorService e]
  (reify IClock
    (clock-in [_ interval-ms f]
      (let [fut (.schedule e ^Runnable f
                  (long (* (double interval-ms) 1e3))
                  TimeUnit/MICROSECONDS)]
        (fn [] (.cancel fut false))))
    (clock-every [_ delay-ms period-ms f]
      (let [fut (.scheduleAtFixedRate e ^Runnable f
                  (long (* (double delay-ms) 1e3))
                  (long (* (double period-ms) 1e3))
                  TimeUnit/MICROSECONDS)]
        (fn [] (.cancel fut false))))))

(defonce ^:private default-scheduler
  (delay
    (doto (ScheduledThreadPoolExecutor.
            1
            (reify ThreadFactory
              (newThread [_ r]
                (doto (Thread. ^Runnable r "filament-scheduler")
                  (.setDaemon true)))))
      (.setRemoveOnCancelPolicy true))))

(defonce ^:private default-clock
  (delay (scheduled-executor->clock @default-scheduler)))

(def ^:dynamic *clock*
  "The active clock for `in`/`every`/`at`. Rebind with `with-clock` for
  testing. Defaults to a single shared daemon scheduler."
  (reify IClock
    (clock-in    [_ i f]   (clock-in    @default-clock i f))
    (clock-every [_ d p f] (clock-every @default-clock d p f))))

(defmacro with-clock
  "Binds `*clock*` to `clock` for the duration of `body`."
  [clock & body]
  `(binding [*clock* ~clock] ~@body))

;; ---------------------------------------------------------------------------
;; Mock clock
;; ---------------------------------------------------------------------------

(defn- cancel-on-exception [f cancel-fn]
  (fn []
    (try (f)
         (catch Throwable t
           (cancel-fn)
           (throw t)))))

(defn mock-clock
  "Creates a deterministic clock for tests. Use with `with-clock` and
  drive time forward via `advance`. Mirrors `manifold.time/mock-clock`."
  ([] (mock-clock 0))
  ([^double initial-time]
   (let [now-a  (atom initial-time)
         events (atom (sorted-map))]
     (reify
       IClock
       (clock-in [this interval-ms f]
         (let [t      (+ ^double @now-a ^double interval-ms)
               cancel (fn []
                        (swap! events #(cond-> %
                                         (contains? % t) (update t disj f))))]
           (swap! events update t (fnil conj #{}) f)
           (advance this 0)
           cancel))
       (clock-every [this delay-ms period-ms f]
         (assert (< 0.0 ^double period-ms))
         (let [period  (atom period-ms)
               cancel  #(reset! period -1)
               wrapped (with-meta (cancel-on-exception f cancel)
                         {::period period})]
           (clock-in this (max 0.0 ^double delay-ms) wrapped)
           cancel))

       IMockClock
       (now [_] @now-a)
       (advance [this t]
         (let [limit (+ ^double @now-a ^double t)]
           (clojure.core/loop []
             (if (or (empty? @events)
                     (< limit ^double (key (first @events))))
               (do (reset! now-a limit) nil)
               (let [[t fs] (first @events)]
                 (swap! events dissoc t)
                 (reset! now-a t)
                 (doseq [f fs]
                   (let [period (some-> f meta ::period deref)]
                     (when (or (nil? period) (< 0.0 ^double period))
                       (try
                         (f)
                         (when period (clock-in this period f))
                         (catch Throwable _ nil)))))
                 (recur))))))))))

;; ---------------------------------------------------------------------------
;; Public scheduling API
;; ---------------------------------------------------------------------------

(defn- unwrap-completion-cause ^Throwable [^Throwable t]
  (if-let [c (.getCause t)] c t))

(defn in
  "Run `f` after `ms` milliseconds on a virtual thread and return a
   Filament that resolves to `f`'s result (auto-unwrapping if `f`
   returns a deferred). Cancelling the returned Filament cancels the
   pending tick if it has not fired, or interrupts the running body if
   it has. A throw inside `f` propagates as the Filament's error with
   frames rooted in the vthread that ran `f`."
  [ms f]
  (let [out       ^Filament (impl/deferred)
        out-cf    ^CompletableFuture (.cf out)
        child-ref (AtomicReference.)
        body      (fn []
                    (when-not (.isDone out-cf)
                      (let [child ^Filament (impl/filament
                                              (fn [] (d/deref-if-deferrable (f))))]
                        (.set child-ref child)
                        (.whenComplete ^CompletableFuture (.cf child)
                          (reify BiConsumer
                            (accept [_ v e]
                              (if e
                                (.completeExceptionally out-cf
                                  (unwrap-completion-cause e))
                                (.complete out-cf v))))))))
        cancel    (clock-in *clock* (double ms) ^Runnable body)]
    (.whenComplete out-cf
      (reify BiConsumer
        (accept [_ _ e]
          (when e
            (cancel)
            (when-let [child ^Filament (.get child-ref)]
              (d/cancel! child))))))
    out))

(defn every
  "Run `f` on a virtual thread every `ms` milliseconds (after
  `initial-delay` ms, default 0). Returns a Filament that stays
  unrealized until cancelled or until a tick throws.

  Matches `manifold.time/every`: a throw on any tick automatically
  cancels the schedule; the throwable becomes the Filament's error so
  callers can observe failures by deref'ing the returned Filament."
  ([ms f] (every ms 0 f))
  ([ms initial-delay f]
   (let [out           ^Filament (impl/deferred)
         out-cf        ^CompletableFuture (.cf out)
         cancel-holder (volatile! nil)
         tick          (fn []
                         (when-not (.isDone out-cf)
                           (let [child ^Filament (impl/filament f)]
                             (.whenComplete ^CompletableFuture (.cf child)
                               (reify BiConsumer
                                 (accept [_ _ e]
                                   (when e
                                     (.completeExceptionally out-cf
                                       (unwrap-completion-cause e)))))))))
         cancel        (clock-every *clock*
                         (double initial-delay) (double ms) ^Runnable tick)]
     (vreset! cancel-holder cancel)
     (.whenComplete out-cf
       (reify BiConsumer
         (accept [_ _ _]
           (when-let [c @cancel-holder] (c)))))
     out)))

(defn at
  "Schedule `f` to run at `timestamp` (ms since epoch). Returns a
  Filament resolving to `f`'s value."
  [^long timestamp f]
  (in (max 0 (- timestamp (System/currentTimeMillis))) f))
