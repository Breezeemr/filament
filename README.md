# Filament

A Manifold-compatible deferred library for Clojure on JDK 21+, where
composition is synchronous on virtual threads and stack traces survive.

Manifold is a surface woven from many threads; a filament is one of
those threads, followed end-to-end. That's the whole design thesis —
stop slicing a logical computation across executors and callbacks, and
let one virtual thread carry it from start to finish.

## Why

Manifold's `chain` is fast and correct, but a failure in step 7 of a
10-step pipeline gives you a stack trace rooted at a pool worker,
wrapped in an `ExecutionException`, with no pointer to the code that
kicked off the chain. On Loom you don't need any of that machinery:
`chain` is just `(reduce (fn [v f] (deref-if-deferrable (f v))) @d fs)`
on a virtual thread, and when step 7 throws the JVM's own stack
contains steps 1-7 plus the caller. No unwrapping, no "where did this
come from", no executor frames.

Filament is that, plus a manifold-compatible API so it drops into
existing handlers without rewrites.

## Status

v0.1-alpha. Core deferred API (`deferred`, `success-deferred`,
`error-deferred`, `chain`, `catch`, `finally`, `zip`, `alt`, `timeout!`,
`let-flow`, `loop`, `cancel!`) is implemented, tested, and benchmarked.
Manifold interop bridge is in place. No streams yet.

## Requirements

- **JDK 21 or newer**, with `--enable-preview` for `StructuredTaskScope`.
  Both JDK 21 and JDK 25 are supported; the fork implementation is
  split into two namespaces and dispatched at first use.
- Clojure 1.12+.

## Install

Not yet published to Clojars. For now:

```clojure
;; deps.edn
{:deps
 {io.github.Breezeemr/filament
  {:git/sha "<sha>"}}}
```

## Quickstart

```clojure
(require '[filament.core :as f])

;; Basic chain
@(f/chain (f/success-deferred 1) inc inc inc)
;; => 4

;; let-flow — like let, but derefs deferred bindings
@(f/let-flow [user   (fetch-user 42)
              orders (fetch-orders user)
              total  (sum-orders orders)]
   {:user user :total total})

;; Parallel work
@(f/zip (f/filament #(slow-query :a))
        (f/filament #(slow-query :b))
        (f/filament #(slow-query :c)))
;; => [result-a result-b result-c]

;; Timeouts
@(f/timeout! (f/filament #(slow-thing)) 500 :fell-back)

;; Error handling with real stack traces
(try
  @(f/chain (f/success-deferred 0)
            inc
            inc
            (fn [_] (throw (ex-info "boom" {:step 3}))))
  (catch clojure.lang.ExceptionInfo e
    (ex-data e)))
;; => {:step 3}
;;    …and the stack trace shows the anonymous fn, the reduce, and
;;    the caller — no ExecutionException wrapper, no pool-worker frame.
```

## Stack traces

The load-bearing claim: a linear pipeline produces a linear stack
trace. When you throw from step N of a `chain`, you see steps 1..N
plus the caller that kicked off the chain, in one trace, with no
executor noise.

Fork combinators (`zip`, `alt`, `timeout!`) attach the fork-site
`Throwable` as a suppressed exception, so a failure inside one of
the parallel children still tells you where the parallelism was
introduced:

```clojure
(try @(f/zip (f/success-deferred 1)
             (f/filament #(throw (ex-info "child failed" {}))))
     (catch Throwable t
       [(ex-message t)
        (-> t .getSuppressed first .getMessage)]))
;; => ["child failed" "filament: forked from __filament_marker__"]
```

Trace capture is on by default and costs about 100 ns per chain. Turn
it off for hot paths that have measured it and care:

```clojure
(binding [filament.core/*capture-traces* false]
  ...)
```

## Performance

Full criterium benchmark against Manifold's `chain'` on a realized
10-step pipeline (AMD Ryzen AI MAX+ 395):

| Scenario | JDK 21 | JDK 25 |
|---|---|---|
| Filament chain-10 | 116 ns | 117 ns |
| Manifold chain'-10 | 208 ns | 161 ns |
| **Ratio (Fil/Man)** | **0.55x** | **0.73x** |
| Filament zip-4 (4× 1 ms sleep) | 1.17 ms | 1.17 ms |
| Manifold zip-4 | 1.09 ms | 1.09 ms |

Filament is *faster* than Manifold on chain-10 on both JDKs thanks to
an inline fast path: when the seed is a plain value or an
already-realized deferred, the reduce runs synchronously on the
caller's stack, only bailing to a virtual thread when a step returns
an unrealized deferred. See `docs/DESIGN.md` and
`docs/bench-results/` for the full story.

## Manifold interop

`filament.manifold` is an optional bridge. Load it after
`filament.core` and Filaments become drop-in for existing manifold
code — `md/chain` accepts them as seeds, `md/deferrable?` recognizes
them, and `->filament` / `->deferred` do explicit round-tripping.
`filament.core` itself has zero compile-time reference to Manifold
and loads fine without it on the classpath.

See `docs/manifold-interop.md` for the full story.

## What Filament is not

- **Non-blocking on platform threads.** Filament assumes its callers
  are on virtual threads and blocks them freely. Library code that
  must not block a platform carrier (Netty event loops, etc.) should
  keep using Manifold.
- **JDK < 21.** Loom is the entire point; there is no fallback.
- **A streams library.** `manifold.stream` has no equivalent yet.
  Scope is larger and out of the v0.1 cut.

## Testing

```bash
clj -A:dev -M:test -m cognitect.test-runner   # fast suite
clj -M:pinning                                # pinning diagnostic
```

See `docs/testing.md` for details.

## Documentation

- `docs/DESIGN.md` — architecture, error model, API surface, decisions.
- `docs/testing.md` — test suite layout and trace-assertion patterns.
- `docs/benchmarks.md` — how to run benchmarks and what the scenarios
  mean.
- `docs/manifold-interop.md` — the bridge in detail.
- `docs/bench-results/` — dated benchmark output.

## License

Copyright © 2026 Breezy EMR.

Licensed under the Apache License, Version 2.0. See `LICENSE`.
