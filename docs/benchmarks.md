# Benchmarks

Criterium-based microbenchmarks comparing Filament against Manifold on
the scenarios described in `DESIGN.md` § "Benchmark plan and results".
Dated results live in `docs/bench-results/`.

## Running

```bash
clj -M:bench -m filament.chain-bench           # full bench (~10 min)
clj -M:bench -m filament.chain-bench --quick   # quick-bench (~30 s)
```

`--quick` uses criterium's `quick-bench` and is fine for catching
regressions in development. Use the full run when publishing numbers.

All scenarios run on the same JVM invocation so warmup is shared. The
harness shuts down Manifold's executor in a `finally`, so running
back-to-back doesn't leak platform threads.

## Scenarios

1. **Filament 10-step chain.** `(f/chain (f/success-deferred 0) inc ×10)`,
   derefed. Hits the inline fast path because the seed is realized.
2. **Manifold 10-step chain.** Same pipeline through
   `manifold.deferred/chain'` on a 16-thread fixed executor. The
   Manifold baseline for scenario 1.
3. **Filament 10-step chain, no trace.** Scenario 1 with
   `*capture-traces*` bound to `false`. Isolates trace-capture cost
   from the rest of the path.
4. **Filament zip of 4 sleepers.** `(f/zip (repeatedly 4 #(f/filament #(Thread/sleep 1))))`.
   Tests the fork path, not the chain path.
5. **Manifold zip of 4 sleepers.** Baseline for scenario 4.
6. **1000 concurrent chains.** Spawn 1000 vthreads each running scenario
   1; report total wall time. Catches scheduler contention, not
   per-call cost.

## Bar

Filament should be **within 2x of Manifold** on scenarios 1 and 4.
Anything outside that is a regression.

When scenario 1 blew past this bar at ~92x on the first implementation,
the response was to find the hot path — not to lower the bar. If the
ratio slips again, add a regression note to the current bench-results
file with the top-of-profile hotspot before considering a bar change.

## Current results

See `docs/bench-results/2026-04-14.md` for the most recent run. As of
that date, both JDK 21 and JDK 25 pass both scenarios on an AMD Ryzen
AI MAX+ 395, with scenario 1 actually running *faster* than Manifold
(0.73x on JDK 21, 0.94x on JDK 25) thanks to the chain inline fast
path — see `DESIGN.md` § "`chain` is `reduce` over `deref`, with an
inline fast path".

## Running on a specific JDK

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=$JAVA_HOME/bin:$PATH \
  clojure -M:bench -m filament.chain-bench --quick
```

The fork impl is selected at first use based on the presence of
`StructuredTaskScope$Joiner` (JDK 25) or falls back to
`ShutdownOnFailure`/`ShutdownOnSuccess` (JDK 21). Both JDKs require
`--enable-preview`, which is set in the `:bench` alias.

## Pitfalls observed in practice

- **Criterium ordering noise.** On quick-bench runs, scenario 3 (no
  trace) has repeatedly clocked slower than scenario 1 (with trace).
  Skipping a `Throwable` allocation cannot be slower in absolute
  terms; this is criterium/JIT ordering noise. A full-bench run or
  randomized scenario order would confirm. The ratio test still
  passes, so we haven't chased it.
- **Manifold `chain'` is only fast on realized seeds.** The 10-step
  chain benchmark uses `success-deferred` as the seed, which Manifold
  hits synchronously on the caller. A fair comparison against
  Manifold's *unrealized* path would use a pending deferred completed
  from another thread; we don't currently bench that.
- **Warmup matters a lot on vthread paths.** The harness runs one
  warmup pass over all scenarios before measuring. Removing that pass
  makes the first scenario look ~3x slower than it really is.
- **`fork_bench.clj`** is a secondary quick-bench for zip/alt/timeout,
  meant to catch regressions in the fork path without the full
  chain-bench runtime. It's not checked against Manifold.
