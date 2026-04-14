# Testing

Two test suites with different purposes:

## Fast suite — `clj -A:dev -M:test -m cognitect.test-runner`

Runs under `-Djdk.tracePinnedThreads=full`, excludes `^:pinning`-tagged
tests. ~1 second wall time. Covers correctness, error propagation, trace
attachment, manifold interop, and `let-flow`/`loop` behavior. Run on
every change.

## Pinning suite — `clj -M:pinning`

Spawns a sub-JVM running `filament.bench.hot-path/run!` under
`-Djdk.tracePinnedThreads=full` and asserts stderr contains no "Thread
is pinned" lines. Alias-gated because it's slow (spawns a JVM). Run
before pushing to catch any accidental `synchronized` / legacy blocking
call that would pin a carrier.

Note: JDK 24+ (JEP 491) removed `synchronized`-block pinning, so on JDK
25 the test cannot be manually demonstrated to fail with a `locking`
block. It still catches real pinning (native frames, legacy channels,
`Object.wait`, etc.) and is worth keeping as a regression guard.

## Trace assertion helpers

`test/filament/trace_test.clj` uses two helpers worth reusing when
adding new trace tests:

```clojure
(defn- frame-classes [^Throwable t]
  (mapv #(.getClassName ^StackTraceElement %) (.getStackTrace t)))

(defn- no-executor-noise? [frames]
  (not-any? #(re-find #"ThreadPoolExecutor|ForkJoinWorkerThread|ForkJoinPool" %)
            frames))
```

Two patterns show up repeatedly:

**Linear-chain assertion.** Check that the main stack trace contains
the caller frame (a class matching `filament\.<some>_test`) and no
executor frames.

```clojure
(let [e (try @(f/chain (f/success-deferred 0)
                       inc inc
                       (fn [_] (throw (ex-info "boom" {:tag :chain}))))
              (catch Throwable t t))
      frames (frame-classes e)]
  (is (= :chain (:tag (ex-data e))))
  (is (no-executor-noise? frames))
  (is (some #(re-find #"filament\.trace_test" %) frames)))
```

**Fork-site assertion.** For `zip`/`alt`/`timeout!`, check that the
root cause is unwrapped and that exactly one suppressed Throwable
carries the `forked from`/`timeout at` marker.

```clojure
(let [e    (try @(f/zip (f/filament #(throw (ex-info "child" {}))))
                (catch Throwable t t))
      sups (filter #(re-find #"forked from" (or (.getMessage ^Throwable %) ""))
                   (.getSuppressed e))]
  (is (= 1 (count sups)))
  (is (some #(re-find #"filament\.trace_test" %)
            (frame-classes (first sups)))))
```

## Gotchas

- `.getSuppressed` returns a Java array. Wrap it in `vec` or `seq`
  before counting or `clojure.test` prints `#object[[Ljava.lang.Throwable ...]`
  and you'll spend ten minutes debugging a passing test.
- Pinning output goes to `System.err`, not `System.out`. If you write a
  new shell-out-based diagnostic, capture stderr explicitly.
- The suppressed-trace tests sometimes see *two* suppressed Throwables
  on zip failures: a deref-site trace from the outer `@` plus the
  fork-site trace. Filter by marker label (`"forked from"` /
  `"deref site"` / `"timeout at"`) rather than asserting an exact
  count.
- `filament.core` excludes `catch`, `finally`, and `loop` from the
  `clojure.core` refer; inside filament-core code, qualify them as
  `clojure.core/catch` etc. Inside `try` forms the `catch`/`finally`
  special-form names are resolved syntactically, not as vars, so
  shadowing them at the ns level is fine.
- To disable trace capture inside a test, `binding`
  `filament.impl/*capture-traces*` (not the `filament.core` alias) —
  the `.refer` re-export puts the var in `ns-refers`, not
  `ns-interns`, and some macro contexts can't resolve it through
  `filament.core`.
