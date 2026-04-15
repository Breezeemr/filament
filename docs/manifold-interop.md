# Manifold interop

`filament.manifold` is an optional bridge namespace. Requiring it adds
Manifold protocol implementations to `filament.impl.Filament` and
exposes explicit conversion functions. `filament.deferred` remains loadable
on a classpath with no Manifold present, and has no compile-time
reference to Manifold.

## Load order

```clojure
(require 'filament.deferred)
(require 'filament.manifold)  ; must come after filament.deferred
```

The bridge uses protocol extension (not `extend-type` on the deftype),
so `filament.impl.Filament` must already be loaded. In practice this
means always requiring `filament.deferred` first.

Manifold is only on the classpath under `:dev`, `:test`, and `:bench`.
The main `:deps` map has no Manifold dependency — adding one would
undermine the core pitch that `filament.deferred` loads standalone.

## What the bridge does

- **Extends `manifold.deferred/Deferrable`** on `Filament` with a
  `to-deferred` impl that bridges via `CompletableFuture.whenComplete`
  and unwraps errors. Manifold's `->deferred` routes through this.
- **Note on protocol choice.** The original sketch used `extend` on
  `manifold.deferred/IDeferred`, but in Manifold 0.4.x `IDeferred` is a
  `definterface+` (a Java interface), not a protocol, so `extend`
  doesn't work. `Deferrable` is a real protocol and handles the same
  cases.
- **Filament already implements `IPending` and `IDeref`**, so
  Manifold's own `deferrable?` / `chain` recognize it natively without
  any protocol wiring on our side.
- **`->filament`** — adapts any Manifold-`Deferrable` to a Filament.
  Round-trip through `->filament` on an existing Filament is a no-op
  (instance check first).
- **`->deferred`** — adapts a Filament to a `manifold.deferred/deferred`.

## `deferred?` delegation

`filament.deferred/deferred?` recognizes Manifold deferreds when Manifold
is on the classpath, via a lazily-resolved delegate:

```clojure
(def ^:private manifold-deferred?-fn
  (delay
    (try
      (require 'manifold.deferred)
      (resolve 'manifold.deferred/deferred?)
      (catch Throwable _ nil))))
```

`filament.deferred` itself never references Manifold by name, so loading
the namespace on a Manifold-less classpath is fine — the delay stays
nil and only the `Filament` instance check runs.

## Round-trip semantics

- `@(fm/->filament (md/success-deferred 1))` → `1`
- `@(fm/->deferred (f/success-deferred 2))` → `2` (via Manifold blocking deref)
- A Filament passed directly to `md/chain`: works, because Manifold
  sees an `IPending`+`IDeref` value.
- A Manifold deferred as a step in `f/chain`: works, because
  `deref-if-deferrable` calls the lazy delegate and derefs it.
- Errors are unwrapped in both directions — no `ExecutionException`
  on the Manifold side, no `manifold.deferred.ErrorDeferred` wrapper
  on the Filament side.

## Verifying core-only loads

```bash
clojure -Sdeps '{:paths ["src"]}' -e "(require 'filament.deferred) :ok"
```

Should print `:ok`. If you see a `ClassNotFoundException` for anything
under `manifold/`, something in `filament.deferred` has started referencing
Manifold directly and needs to be pushed behind the lazy delegate.
