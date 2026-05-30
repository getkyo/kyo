# Steering rules for the kyo-tasty unsafe-cleanup campaign

This campaign tightens the AllowUnsafe surface left after the
audit-fixes campaign (commits a04457b65..f70589ab9). 46 `import
AllowUnsafe.embrace.danger` sites and 5 raw java.util.concurrent.atomic
imports in shared/main must drop to true §839 case 3 boundaries.

## Boundary doctrine (CONTRIBUTING.md §794-§897)

1. ORDER OF PREFERENCE for unsafe code:
   - (a) Propagate the proof: method takes `(using AllowUnsafe)` in
     its signature; the caller is responsible for the suspension.
   - (b) Suspend in Sync: `Sync.Unsafe.defer { ... }` at a clear boundary.
   - (c) `import AllowUnsafe.embrace.danger` ONLY at external callbacks,
     app boundaries, and module-load init.

2. SCOPE NARROWLY (§844): NEVER on a constructor or class-level
   position. Take it only on specific methods that need it.

3. PREFER THE SAFE TYPE (§857): hold `AtomicInt`, not `AtomicInt.Unsafe`.
   Drop to `.unsafe` only inside methods that already have AllowUnsafe.

4. EVERY METHOD THAT PERFORMS SIDE EFFECTS WITHOUT SUSPENSION MUST
   TAKE `(using AllowUnsafe)` IN ITS SIGNATURE. Hard rule.

## kyo-core primitives verified for use (signature + perf)

| API | Shape | Hot-path overhead |
|---|---|---|
| AtomicInt.Unsafe | opaque = j.u.c.a.AtomicInteger; inline + (using AllowUnsafe) | byte-identical to raw |
| AtomicLong.Unsafe | same | same |
| AtomicBoolean.Unsafe | same | same |
| AtomicRef.Unsafe[A] | opaque[A] = j.u.c.a.AtomicReference[A]; inline | same |
| LongAdder.Unsafe | opaque = j.LongAdder | beats AtomicLong under contention |
| Stat.Counter.unsafe field | UnsafeCounter wraps j.LongAdder | use `.unsafe.inc(using AllowUnsafe)` on hot paths |

DO NOT use Stat.Counter.add(v)(using Frame) on hot paths — allocates
a Sync.Unsafe.defer thunk per call.

## What stays bespoke (verified)

- OnceCell — kyo-core has no Once/Lazy primitive. Current shape
  takes (using AllowUnsafe) on get() — correct per the doctrine.
- Interner shards — kyo-core has no AtomicReferenceArray opaque
  wrapper. Either keep AtomicReferenceArray or switch to
  Array[AtomicRef.Unsafe[Bucket]]; measure before choosing.
- Unpickler cursors (bare `var pos`) — single-fiber call-stack-local,
  NOT shared state. Rule 1 doesn't apply. Stay as var.

## Process

- Agents NEVER `git add` or `git commit`. Supervisor commits.
- Every phase runs `kyo-tasty/test`, `kyo-tastyJS/test`,
  `kyo-tastyNative/test` (not just Test/compile — Phase 23a lesson).
- Em-dash / en-dash check on every diff.
- No backwards-compatibility shims; no default params on private APIs.
- No `import AllowUnsafe.embrace.danger` added in new code. Existing
  ones are migrated to either propagation or §839 case 3 with a
  `// flow-allow:` annotation.
