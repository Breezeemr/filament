# Filament

A Manifold-compatible deferred/stream library for Clojure on JDK 21+, where
composition is synchronous on virtual threads and stack traces survive.

The name: Manifold is a surface woven from many threads; a filament is one of
those threads, followed end-to-end. That is the whole design thesis — stop
slicing a logical computation across executors and callbacks, and let one
virtual thread carry it from start to finish.

## Goals

1. **API parity with Manifold** for the 80% surface area people actually use:
   `deferred`, `success-deferred`, `error-deferred`, `chain`, `catch`,
   `finally`, `zip`, `timeout!`, `let-flow`, `loop`, plus the `IDeferred` /
   `IDeferrable` protocols so existing Manifold-aware code (Aleph handlers,
   `d/->deferred` conversions) interops without a shim at every call site.
2. **Linear pipelines produce linear stack traces.** A `chain` of N synchronous
   steps, when the Nth throws, must show all N frames plus the caller that
   kicked off the chain, in one trace, with no `ExecutionException` wrapping
   and no executor-dispatch noise.
3. **Fork points degrade gracefully, not silently.** Parallel combinators
   (`zip`, `alt`, timeouts) attach the submit-site stack as a suppressed
   exception so the trace at the join point still points back to the code that
   launched the work.
4. **Drop-in for app code.** Requiring Filament instead of Manifold in an
   Aleph/Ring handler should Just Work, modulo the JDK version bump.

## Non-goals

- Running on JDK < 21. Loom is the entire point; we will not carry a
  non-virtual-thread fallback.
- Preserving Manifold's "never block a carrier thread" invariant. Filament
  blocks virtual threads freely and assumes the caller is already on one.
  Library code that must stay non-blocking on platform threads should keep
  using Manifold.
- Reimplementing `manifold.stream` in the first cut. Streams come after
  deferreds are stable; the same principles apply but the scope is larger.

## Core type

```clojure
(deftype Filament [^CompletableFuture cf ^Thread submit-thread submit-trace])
```

Backed by `CompletableFuture` for three reasons: it already has the state
machine (pending / realized / errored / cancelled), it interops with every
Java async API we might want to bridge, and `.get` on a `CompletableFuture`
from a virtual thread parks the vthread rather than pinning the carrier.

`submit-trace` is a lazily-captured `Throwable` recorded at construction time.
It is never thrown on its own; it is attached as a suppressed exception on any
error that crosses a fork boundary, so join-site traces can point back to the
fork site.

`IDeferred` / `IDeferrable` are implemented so existing Manifold code treating
Filaments as deferreds (and vice versa) works without explicit conversion.

## `chain` is `reduce` over `deref`, with an inline fast path

The load-bearing simplification is that `chain` is a reduce. The first
shipped version was exactly this:

```clojure
(defn chain [d & fs]
  (filament
    (fn []
      (reduce (fn [v f] (f v)) @d fs))))
```

That passed every correctness test and produced the linear traces we
wanted, but it unconditionally submitted a virtual thread for the whole
reduction — about **13 µs of floor cost** for vthread submit + mount +
cross-thread `.get` + two `Throwable` captures, on an AMD Ryzen AI MAX+ 395
/ JDK 25. Manifold's `chain'` on an already-realized `success-deferred`
runs synchronously on the caller (~150 ns), and scenario 1 of the
benchmark was ~92x slower until we fixed it.

The fix: run the reduce inline on the caller when the seed is a plain
value or an already-realized deferred, and only bail to `impl/filament`
when a step returns an *unrealized* deferred. At that point, the
remainder of the reduce resumes on a vthread seeded by that deferred.
Errors raised inline are caught and wrapped in `error-deferred`, so the
return type is always a `Filament`.

```clojure
(defn chain [d & fs]
  (if (and (deferred? d) (not (realized? d)))
    (chain-rest-on-vthread d fs)
    (try
      (clojure.core/loop [v  (deref-if-deferrable d)
                          fs (seq fs)]
        (if fs
          (let [r ((first fs) v)]
            (if (and (deferred? r) (not (realized? r)))
              (chain-rest-on-vthread r (next fs))
              (recur (deref-if-deferrable r) (next fs))))
          (impl/success-deferred v)))
      (catch Throwable t
        (impl/error-deferred t)))))
```

This mirrors Manifold's primed fast path. Stack traces are still linear
— in fact *cleaner*, because the reduction runs on the caller's own
stack with no vthread entry frame interposed. The benchmark result went
from ~92x slower than Manifold to **0.73x on JDK 21, 0.94x on JDK 25** —
Filament is now faster than Manifold on the realized-seed chain-10
microbenchmark on both JDKs. See `docs/bench-results/2026-04-14.md` for
the full run.

Because each `f` is called on the same thread in a plain `reduce`, when
`f` throws, the JVM's own stack contains the throwing site inside `f`,
then `reduce`, then the caller. On the fast path the caller is directly
on that stack; on the slow path it's on the vthread stack, which still
doesn't interleave with any executor.

`let-flow` expands to a `filament` body wrapping a plain `let` whose
binding values are passed through `deref-if-deferrable`. It does not go
through `chain`, so every `let-flow` always runs on a vthread — which
is fine because `let-flow`'s value proposition is the `let`-shaped
sugar, not the latency floor. The macro does not need to be clever;
the cleverness is in not hopping executors.

## Fork points: `zip`, `alt`, `timeout!`

These are the cases where we genuinely need more than one thread of control.
The pattern is always the same: open a `StructuredTaskScope`, fork each
child, join, and propagate the first failure.

The submit-site `Throwable` captured at `zip` construction is attached as
a suppressed exception on whatever the scope raises. So a failure inside
one of the forked children surfaces with (a) the child's own trace,
unwrapped, and (b) a suppressed "forked from here" pointer back to the
`zip` call site. That is strictly more information than Manifold gives
you today, and it costs one stack capture per fork.

`timeout!` is `zip` with a wall-clock deadline; `alt` is the
first-successful-result variant. Same shape.

### JDK 21 vs JDK 25 API split

`StructuredTaskScope` is a preview feature on both JDK 21 and JDK 25, and
the API shape is materially different between them:

- **JDK 21** exposes concrete subclasses:
  `StructuredTaskScope$ShutdownOnFailure` and `ShutdownOnSuccess`, with
  `.join()` / `.joinUntil(deadline)` / `.throwIfFailed()` / `.result()`.
  Timeouts are driven by `joinUntil` against an `Instant`.
- **JDK 25** removed those subclasses and replaced them with a
  `Joiner`-factory shape: `StructuredTaskScope.open(Joiner)` with
  `Joiner/allSuccessfulOrThrow`, `Joiner/anySuccessfulResultOrThrow`, and
  `withTimeout(Duration)` on the scope config function. Failures surface
  as `StructuredTaskScope$FailedException`.

The same code cannot compile against both, because the Clojure compiler
resolves static class references at compile time. We split the fork
implementation into two small namespaces — `filament.impl.fork-21` and
`filament.impl.fork-25` — each exporting three functions (`zip-all`,
`alt-any`, `with-timeout`) against its JDK's API shape. `filament.deferred`
detects which is available at first use via
`Class/forName "java.util.concurrent.StructuredTaskScope$Joiner"`, then
`require`s the matching namespace and caches the fn lookups in a delay.

`filament.deferred/zip` / `alt` / `timeout!` keep the trace-capture and
fork-site-attach logic in one place; the JDK-specific namespaces are
pure scope-execution helpers. This means the JDK 21 code path is *never
compiled* on a JDK 25 host and vice versa, so neither side needs to use
reflection.

Both JDKs still require `--enable-preview`. The shared executor
(`Executors.newVirtualThreadPerTaskExecutor()`) is stable on both JDKs
and does not need `--enable-preview`.

The `TimeoutException` types are normalized: on JDK 21 `joinUntil`
throws `java.util.concurrent.TimeoutException` directly, and on JDK 25
we catch `StructuredTaskScope$TimeoutException` and rewrap as the
plain `TimeoutException` so `timeout!`'s fallback branch is
JDK-independent.

## Error handling

`catch` and `finally` wrap the body in a Clojure `try`. Because the body runs
synchronously on the vthread, `try` Just Works — no `on-realized` callback, no
second executor, no `ExecutionException` unwrapping. The exception the user
catches is the exception that was thrown.

The one place we have to be careful: when a Filament is realized with an
error from a *different* thread (e.g., it was completed by a Netty I/O
callback), the stack on the throwable is the Netty thread's stack, not the
caller's. We handle this the same way as fork points: attach the deref-site
trace as suppressed on the way out of `@d`. This means every cross-thread
handoff costs a stack capture, which is the price of clarity; it can be
disabled via a dynamic var for hot paths that have measured it and care.

## Executor story

Filament ships one executor: `Executors.newVirtualThreadPerTaskExecutor()`,
held in a `delay` so requiring the namespace doesn't eagerly spin up Loom on
JDK 17 and crash. Every `filament` constructor submits to it unless the caller
passes their own `ExecutorService`. There is no thread pool to size, no
`manifold.executor` indirection, no `with-executor` dynamic binding to
remember. If you want work on a specific executor, pass it; otherwise,
vthreads.

## Interop with Manifold

A one-namespace bridge, `filament.manifold`:

- `->filament` — takes anything `IDeferrable` and returns a Filament that
  derefs it (on a vthread, so blocking is free).
- `->deferred` — takes a Filament and returns a `manifold.deferred/deferred`
  that is completed via `on-realized` when the Filament resolves.

Aleph handlers that return a Filament work because Filament implements
`IDeferred` directly; the bridge is only needed when existing code
specifically type-checks for `manifold.deferred.Deferred`.

## What we are explicitly not doing

- **No `chain'` / primed variants as a separate function.** Manifold has
  these because callback dispatch is expensive and it wants to elide it
  when the value is already realized. Filament's regular `chain` has the
  primed fast path built in: if the seed is realized, the reduce runs
  inline on the caller. There is no separate `chain'` to remember.
- **No custom executor per combinator.** Manifold lets you say "run this
  `chain` step on executor X." We don't. Use `(filament #(with-executor ...))`
  or just call the blocking thing directly — you're on a vthread.
- **No `loop` macro tricks.** `manifold.deferred/loop` exists because you
  can't recur across a callback boundary. On a vthread, `clojure.core/loop`
  works fine; we provide `filament.deferred/loop` only as an API-compat
  alias that expands to `loop` + `chain`.

## Implementation order

1. `Filament` deftype, `IDeferred` impl, `filament` constructor,
   `success-deferred` / `error-deferred`, `deref` / `@`.
2. `chain`, `catch`, `finally`. Verify stack traces by hand: write a
   three-step chain where step 3 throws, print the trace, confirm all three
   steps plus the test caller are visible with no executor frames.
3. `zip`, `alt`, `timeout!` via `StructuredTaskScope`. Verify suppressed
   submit-site traces at fork boundaries.
4. `let-flow` macro. This is pure sugar over `chain` and should be a
   ~30-line macro.
5. Manifold interop bridge. Test against a real Aleph handler.
6. Benchmark: a 10-step `chain` under load, compared to Manifold's `chain'`
   on a fixed thread pool. We expect Filament to be competitive, not
   necessarily faster; the win is ergonomic, not throughput.
7. `manifold.stream` equivalent. Out of scope for v0.1.

## Project layout

```
filament/
  deps.edn
  docs/
    DESIGN.md                 ; this file
    tasks/                    ; per-step implementation tasks
    bench-results/            ; dated criterium output + notes
  src/filament/
    core.clj                  ; public API; the namespace users require
    impl.clj                  ; Filament deftype, unwrap-get, trace helpers
    deferred.clj              ; (currently a stub; deferred- API lives in core)
    manifold.clj              ; optional interop bridge; loaded on demand
    impl/
      fork_21.clj             ; JDK 21 ShutdownOnFailure/ShutdownOnSuccess impl
      fork_25.clj             ; JDK 25 open(Joiner) impl
  test/filament/
    core_test.clj
    chain_test.clj
    fork_test.clj
    trace_test.clj            ; asserts on captured Throwables
    let_flow_test.clj
    interop_test.clj
    pinning_test.clj          ; runs under -Djdk.tracePinnedThreads=full
    smoke_test.clj
  bench/filament/
    chain_bench.clj           ; criterium, vs manifold.deferred/chain'
    fork_bench.clj
    bench/hot_path.clj        ; exerciser used by pinning_test
```

`deps.edn` pins `:jvm-opts ["--enable-preview" ; not needed on 21+, kept for
clarity "-Djdk.tracePinnedThreads=full"]` under a `:test` alias. The main
alias has no JVM flags so downstream apps don't inherit our diagnostics.

## Public API surface

All of the following live in `filament.deferred` and are re-exported from
`filament.deferred` under Manifold-compatible names.

**Constructors**

- `(filament f)` / `(filament f executor)` — submits `f` (a 0-arg fn) to the
  vthread executor (or a caller-supplied `ExecutorService`) and returns a
  `Filament`. Captures `submit-trace` at call time.
- `(success-deferred v)` — `Filament` already completed with `v`.
- `(error-deferred e)` — `Filament` already completed exceptionally with `e`.
- `(deferred)` — pending `Filament` you can later resolve with `success!` /
  `error!`. No backing vthread; it's just a handle to a pending
  `CompletableFuture`.
- `(success! d v)` / `(error! d e)` — resolvers. Return `true` if this call
  transitioned the deferred, `false` if it was already realized.
- `(deferred? x)` — true if `x` satisfies `IDeferred` (covers Manifold and
  Filament alike).

**Composition**

- `(chain d f1 f2 … fN)` — reduce `fs` over `@d`, unwrapping any intermediate
  `IDeferred`. Returns a `Filament`.
- `(catch d handler)` / `(catch d err-class handler)` — handler runs on the
  same vthread; its return value becomes the new result. Re-throwing from
  `handler` produces a new error-completed `Filament`.
- `(finally d f)` — `f` is a 0-arg side-effecting fn; original value/error
  passes through unchanged.
- `(zip d1 … dN)` — returns a `Filament` resolving to a vector of results.
  Uses `StructuredTaskScope$ShutdownOnFailure`.
- `(alt d1 … dN)` — first-to-succeed wins; uses `ShutdownOnSuccess`.
- `(timeout! d ms)` / `(timeout! d ms fallback)` — races `d` against a
  sleeper; on timeout either errors with `TimeoutException` or resolves to
  `fallback`.
- `(let-flow [bindings] & body)` — macro. Expands each binding to a `chain`
  step; bindings that are themselves `Filament`/`IDeferred` are derefed.
- `(loop [bindings] & body)` — macro. Wraps body in `clojure.core/loop`; the
  `recur` target is the vthread, not a callback. Provided only for API parity.

**Control**

- `(cancel! d)` — see cancellation section below.
- `(realized? d)` — delegates to the underlying `CompletableFuture`.

## `Filament` deftype and protocols

```clojure
(definterface IFilamentInternal
  (^java.util.concurrent.CompletableFuture cf [])
  (^java.lang.Throwable submitTrace [])
  (^java.util.concurrent.atomic.AtomicReference runner []))

(deftype Filament [^CompletableFuture cf
                   ^Throwable submit-trace
                   ^AtomicReference runner   ; holds the vthread once it starts
                   _meta]
  IFilamentInternal
  (cf [_] cf)
  (submitTrace [_] submit-trace)
  (runner [_] runner)

  clojure.lang.IDeref
  (deref [_] (impl/unwrap-get cf submit-trace))

  clojure.lang.IBlockingDeref
  (deref [_ timeout-ms timeout-val]
    (impl/unwrap-get-timed cf submit-trace timeout-ms timeout-val))

  clojure.lang.IPending
  (isRealized [_] (.isDone cf))

  clojure.lang.IObj
  (meta [_] _meta)
  (withMeta [_ m] (Filament. cf submit-trace runner m))

  ;; Implemented when manifold is on the classpath via a protocol extension
  ;; in filament.manifold, not directly on the deftype, so the core namespace
  ;; has zero runtime dependency on manifold.
  )
```

The `runner` `AtomicReference` is populated by the body wrapper the moment
the submitted `Callable` begins executing. Cancellation reads it to interrupt
the correct vthread.

`filament.manifold` uses `extend` to add `manifold.deferred/IDeferred` and
`manifold.deferred/IDeferrable` implementations, reusing the `CompletableFuture`'s
`whenComplete` for `on-realized`. This keeps `filament.deferred` loadable without
manifold on the classpath; the bridge is opt-in.

## Dynamic configuration

```clojure
(def ^:dynamic *capture-traces*
  "When true (the default) Filament captures a Throwable at every submit
   site and every cross-thread deref, and attaches it as a suppressed
   exception on the propagated error. Set to false on hot paths that have
   measured the allocation cost and want it gone."
  true)

(def ^:dynamic *executor*
  "Default executor used by (filament f) when no explicit executor is
   passed. nil means the shared vthread-per-task executor."
  nil)
```

No `with-executor` macro; use `binding`. The shared executor is held in a
top-level `delay` so requiring `filament.deferred` on JDK < 21 fails at first
use, not at load time — useful for tools that scan namespaces.

## Error model (the rules, in order)

1. **Synchronous throw inside a filament body** → the underlying
   `CompletableFuture` is completed exceptionally with *that Throwable*, not
   an `ExecutionException` wrapper. Clojure `try` inside the body catches it
   normally; there is no second executor in the picture.
2. **`@filament` rethrows the cause, not the `ExecutionException`.**
   `impl/unwrap-get` calls `.get`, catches `ExecutionException`, pulls
   `.getCause`, and throws it. `CancellationException` passes through.
3. **Cross-thread deref attaches a suppressed trace.** If the thread calling
   `@d` is not the thread that ran the body (detected via the `runner`
   `AtomicReference`), and `*capture-traces*` is true, a fresh
   `Throwable` captured at the deref site is added as a suppressed exception
   on the cause before rethrow. Labeled `"filament: deref site"`.
4. **Fork combinators attach the submit-site trace as suppressed.** `zip`,
   `alt`, and `timeout!` catch failures from their `StructuredTaskScope`,
   unwrap to the child's root cause, and attach the `submit-trace` of the
   combinator-returned Filament with label `"filament: forked from"`.
5. **Suppressed attachment is idempotent.** Each synthetic Throwable carries
   a unique marker in its message; `impl/add-suppressed-once` walks
   `getSuppressed()` and refuses to double-add. This keeps deep chains of
   `chain` → `zip` → `chain` from producing N copies of the same pointer.
6. **`catch` handlers see the unwrapped cause, never `ExecutionException`.**
   This matches Manifold's `catch` semantics and avoids every porter's first
   bug report.

## Cancellation semantics

Decision: **interrupting-by-default.**

- `(cancel! d)` first tries to complete the backing `CompletableFuture`
  exceptionally with a `CancellationException`. If the body has already
  started (`runner` is non-nil), it *also* calls `.interrupt` on the
  vthread. The vthread's in-flight `.get`, `Thread.sleep`, `park`, blocking
  I/O via NIO channels, etc., will throw `InterruptedException`, which
  propagates up through the filament body and completes the deferred
  exceptionally — with the `CancellationException` taking precedence if it
  was the one that won the race.
- No `cancel!*` advisory variant. Users who want cooperative cancellation
  can close over a `volatile!` flag and check it themselves; we don't need
  two verbs.
- `StructuredTaskScope` handles child cancellation on `shutdown` for us, so
  `zip`/`alt`/`timeout!` get correct cancellation propagation for free.

This is a departure from Manifold. It is documented in the README's
"Porting from Manifold" section with a one-paragraph warning.

## Stack trace verification

The load-bearing claim of the library is "a 3-step chain that throws
produces a linear trace with 3 frames and no executor noise." Test this
directly, not transitively:

```clojure
(deftest chain-trace-is-linear
  (let [e (try @(f/chain (f/success-deferred 0)
                         inc
                         inc
                         (fn [_] (throw (ex-info "boom" {}))))
                (catch Throwable t t))
        classes (->> (.getStackTrace e)
                     (map #(.getClassName %))
                     vec)]
    (is (= "boom" (ex-message e)))
    ;; caller frame present
    (is (some #(re-find #"filament\.trace_test" %) classes))
    ;; no executor noise
    (is (not-any? #(re-find #"ThreadPoolExecutor|ForkJoinWorkerThread" %)
                  classes))))
```

Fork-site tests assert on `(.getSuppressed e)` — exactly one synthetic
Throwable whose top frame is the `zip` call site.

## Pinning audit

`pinning_test.clj` shells out to a sub-JVM (or redirects `System.err` via
`PrintStream`) running the full suite with
`-Djdk.tracePinnedThreads=full`. The captured stderr is asserted empty.
Any accidental `synchronized`, `ReentrantLock`, or legacy blocking call
introduced in the hot path shows up here immediately. This test runs in
CI on every PR; locally it runs only under the `:pinning` alias.

## Benchmark plan and results

`chain_bench.clj` runs, under criterium:

1. Filament: 10-step `chain` starting from `success-deferred`, each step
   an `inc`. Realized seed, so this exercises the inline fast path.
2. Manifold baseline: same 10-step `chain'` on a 16-thread
   `manifold.executor/fixed-thread-executor`.
3. Filament with `*capture-traces* false` — isolate trace-capture cost.
4. Filament `zip` of 4 children each doing `Thread/sleep 1ms`, vs.
   Manifold `zip` on the same pool.
5. 1000 concurrent chains wall-clock.

The bar: Filament within **2x** of Manifold on (1) and (4).

### Results (2026-04-14, AMD Ryzen AI MAX+ 395, quick-bench)

| Scenario | JDK 21 | JDK 25 |
|---|---|---|
| Filament chain-10 | 140 ns | 150 ns |
| Manifold chain'-10 | 191 ns | 160 ns |
| **Ratio (Fil/Man)** | **0.73x** ✅ | **0.94x** ✅ |
| Filament chain-10 (no trace) | 242 ns | 197 ns |
| Filament zip-4 sleepers | 1.12 ms | 1.18 ms |
| Manifold zip-4 sleepers | 1.09 ms | 1.09 ms |
| Ratio zip | 1.03x ✅ | 1.08x ✅ |
| 1000 concurrent chains (wall) | 2.99 ms | 3.47 ms |

**What this tells us.** Filament is faster than Manifold on chain-10 on
both JDKs, which is the scenario that was ~92x *slower* before the
inline fast path landed. Zip-4 is within 10% on both JDKs — latency is
dominated by the 1 ms `Thread/sleep`, so vthread submit overhead is
irrelevant. Manifold itself got ~20% faster on JDK 25 vs JDK 21
(presumably general JIT/vthread improvements), which narrows Filament's
apparent lead to 0.94x there; absolute numbers on both sides improved.

**Odd datapoint:** scenario 5 (chain-10 with traces off) clocks slower
than scenario 1 in quick-bench mode on both JDKs. Skipping a
`Throwable.` allocation cannot make the path slower in absolute terms;
this is almost certainly criterium ordering / JIT noise on a short
quick-bench. A full-bench run with randomized scenario order would
confirm. It does not affect the PASS result.

**Hotspot candidates for further reduction** (if we ever need to push
past Manifold on chain-10):

1. The `clojure.core/loop` inside `chain` allocates a fresh seq every
   step via `next`. A raw `for`-loop over an array of fs would be
   allocation-free.
2. Submit-site `Throwable` construction still costs ~80 ns on the slow
   path (scenario 3 comparison). `fillInStackTrace` is ~80% of that.
3. `deferred?` does a protocol check and (lazily) a manifold check.
   Inlining the Filament instance check as the first branch is cheap.

None of these are load-bearing; the current numbers meet the bar on
both target JDKs.

## Decisions on previously open questions

- **Cancellation:** interrupting-by-default. `cancel!` completes the
  backing CompletableFuture exceptionally and interrupts the running
  vthread (if any). No advisory variant.
- **Stack capture cost:** on by default, togglable via `*capture-traces*`.
  Scenario 3 of the benchmark puts the cost at ~80 ns per chain when
  the body runs inline, and it is dwarfed by vthread submit on fork
  paths. The diagnostic value is the reason the library exists; keeping
  it on by default is the right call.
- **Pinning audit:** `-Djdk.tracePinnedThreads=full` is set in the
  `:pinning` alias, and `pinning_test.clj` shells out to a sub-JVM
  running `filament.bench.hot-path` under that flag, asserting stdout
  and stderr contain no "Thread is pinned" lines. The full test suite
  also runs under the flag in the `:test` alias.

  Note: JDK 24+ (JEP 491) removed `synchronized`-block pinning
  entirely, so on JDK 25 the test cannot be manually demonstrated to
  fail with a `locking` block. The test still catches real pinning
  (native frames, filesystem pinning, etc.) and is worth keeping as a
  regression guard for any future introduction of a pinning call in
  the hot path.
- **Chain fast path:** `chain` runs inline on the caller when the seed
  is a plain value or already-realized deferred. Only bails to a
  vthread when a step returns an *unrealized* deferred. See the
  `chain` section above for the motivation and result.
- **JDK 21 vs JDK 25 fork API:** split into `filament.impl.fork-21` and
  `filament.impl.fork-25`, dispatched at first use. See the fork
  section above. Both JDKs require `--enable-preview`.

## Open questions

*(None blocking v0.1. The items that were here — cancellation,
stack-capture cost, pinning audit — are all decided above.)*

Possible future work:

- **Streams.** `manifold.stream` equivalent. Principles carry over
  (vthread-per-stream-consumer, linear traces), scope is larger.
- **Chain allocation.** See the "hotspot candidates" note under
  benchmarks. Not worth doing until profiling a real app shows `chain`
  in the hot path.
- **Typed `deferred?` fast path.** Skip the manifold check when there
  is provably no manifold on the classpath.
