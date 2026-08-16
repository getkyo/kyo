# Bringing EffectTrace to the proto kernel

## What exists today

Three artifacts are relevant, none wired into the proto:

1. `kyo-kernel2/.../internal/EffectTrace.scala`: the complete carrier and reconstruction machinery. Zero cost
   until something throws: no frames are recorded while a computation runs. When an exception crosses an
   evaluator boundary, the trace is reconstructed from what the evaluator already holds (the failing node and
   the drive stack), attached to the exception as a suppressed carrier, and finally spliced into the stack
   trace, synthesized frames first, plumbing-filtered physical frames after. A 64-frame cap, innermost first,
   fatal errors untouched, NoStackTrace keeps its carrier but skips the splice, and a failure inside the walk
   is swallowed so describing a failure can never replace it.
2. `kyo-kernel2/.../internal/Eval.scala` (commented out): the wiring pattern for the kernel2 evaluator. Three
   interior attach boundaries (operation dispatch, settle of a popped frame, unhandled suspension) plus one
   loop-level catch that splices before rethrowing, all before the finally that truncates the stack.
3. `kyo-kernel/.../internal/Trace.scala`: the mature kernel's alternative design, an always-on 16-slot ring of
   executed frames owned by Safepoint, with pooling, save/copy for fiber forks, and enrich-on-throw.

## Ring vs reconstruction

The two designs answer different questions. The ring shows what already ran (history, newest first); the
reconstruction shows the failing node plus what was still pending (the continuation). The ring costs an array
store and index bump on every frame executed, on the exact rows the proto guards jealously (the fused towers,
where one or two cold bytes have flipped inline budgets three times); it also needs an owner to live on
(Safepoint state, pooling, fork transfer), none of which the proto has. The reconstruction costs nothing until
a throw and needs no state beyond what `Eval.loop` already holds.

kernel2 already made this call and documented it in EffectTrace's header. The proto should inherit it:
port EffectTrace, wire it into the proto evaluator, do not build a ring. The trade to state honestly: an
exception thrown inside a strict map delivery shows the pending continuation and the physical stack, not the
frames that already executed before it. The physical stack compensates more in the proto than anywhere else,
because every `map`/`flatMap`/`handleWith` site mints an anonymous `Transform` class in the user's own
compilation unit, so the raw JVM trace already carries user file/line pairs; the splice's plumbing filter
deliberately never removes them.

## What the proto is missing for a good trace

One model gap: `Arrow.Suspend` carries no `Frame`. `ArrowEffect.suspend` and `suspendWith` already demand
`using inline _frame: Frame` and currently discard it. The suspension site (`ask` at the user's line N) is the
innermost, most valuable element of a trace through a handler, and kernel2's settle boundary leads with exactly
that (`builder.value(suspended)` reads `s.frame`). So:

- add `def frame: Frame` to `Arrow.Suspend`,
- populate it in `suspend`, `suspendWith` (`def frame = _frame`),
- forward it in `Suspend.chain`'s fused re-mint (`def frame = self.frame`).

Cost: one accessor per suspend anon (bytecode per suspend site, a small compile-time delta on the SuspendSites
fixture, no runtime work on any path). Everything else the walk needs is already on the nodes: `Transform.frame`
(map, flatMap, andThen, unit, the handleWith cont arrows, flatten), `Handler.tag` for region labels,
`Handle.cont`, `Chain.a/b`, `Bind.cont`, `Arrow.Eval.entries/tags` for captured segments.

## The port: EffectTrace.scala into kyo.kernel.proto

The carrier, cap, splice, find/carrierOf, and Builder skeleton move essentially verbatim. Three adaptations:

1. Builder node-shape mapping (kernel2 left column, proto right column):

   | kernel2 node        | proto node          | Builder action                                        |
   |---------------------|---------------------|-------------------------------------------------------|
   | `Arrow.AndThen(a,b)`| `Arrow.Chain(a,b)`  | push b, push a                                        |
   | `Arrow.Transform`   | `Arrow.Transform`   | frame(t.frame)                                        |
   | `Arrow.Step`        | `Arrow.Step`        | push tail, frame(head.frame)                          |
   | `Kyo.Suspend`       | `Arrow.Suspend`     | frame(s.frame) (new member)                           |
   | `Kyo.Defer`         | `Arrow.Bind`        | push cont; value(b.value) if it is an Arrow           |
   | `Kyo.Handle*`       | `Arrow.Handle`      | region(h.handler.tag), push h.cont                    |
   | (none)              | `Arrow.Eval` segment| walk entries top-down, tags as region labels          |
   | `Effect.Guard`      | (phase 2)           | push wrapped                                          |

   The stack-entry walk (`entries`) is identical: `stack(i)` from top down to `base`, each entry through
   `value`. Proto stack entries are always Arrows (the typed accessor from the cast-clear work), so the walk is
   total over the table above. A marked entry is a `Handle`, rendered as its region label; its cont sits below
   it and renders on its own.

2. The plumbing filter's prefixes become `kyo.kernel.proto.` (keeping the rule that anonymous user-site
   Transform classes are never filtered; they live in the user's package).

3. `value` gains the `Nested` case: unwrap and recurse once, so a boxed payload that happens to be the failing
   value still renders its computation's frames.

## The wiring: Eval.loop

Mirroring the commented kernel2 pattern onto the proto evaluator's actual shape:

- **Dispatch boundary** (the whole `case s: Suspend` arm body: `find`, handler `run`, cont applications,
  the resume adapter's settled arm): wrap in `try ... catch ex => EffectTrace.attach(ex, s, cur-context,
  stack, base); throw`. This is where handler clauses and fused continuations run user code.
- **Unhandled suspension** (`bug(...)`): attach before the throw escapes, so "unhandled suspension" arrives
  with the effect chain that reached it. Highest-value single wiring point for debugging.
- **Settle boundary** (the settled branch: popped-frame application at the step-protocol call and the three
  `complete` calls): attach with the popped frame as the failing value and the suspension (if the loop tracks
  the last dispatched one, kernel2 kept a `suspended` var for exactly this) leading the reconstruction.
- **Loop-level splice**: a `catch ex => EffectTrace.splice(ex); throw` on the main try, before the existing
  `finally stack.truncate(base)`. Ordering matters twice: attach must happen where the stack is still intact
  (catch runs before finally), and splice must happen per drive so nested drives accumulate: the inner drive
  attaches and splices its slice, the outer drive attaches its own `[base, top)` slice and splices again;
  the carrier and cached physical trace make the second splice cheap and the rendering innermost-first.
- **Strict deliveries at construction time** (map's in-budget arm, outside any drive) need no wiring: there is
  no stack to walk and the physical JVM trace through the user's anon Transform classes is the trace.

The existing throw-cleanup test ("a drive cleans its stack after a throw") pins that attach only reads: the
truncate in finally still runs, and the reconstruction happens strictly before it.

## Phase 2: Effect.catching

kernel2's `Effect.catching` is the user-facing recovery boundary and uses the two frame-shaped attach overloads
(`attach(ex, frame)` for the outer arm, `attach(ex, frame, cont, next)` for the guard arm). The proto has no
`Effect` yet; it is already on the survey backlog. The Guard design ports naturally (proto has `Transform`,
`Bind` for defer, `chain` for the map). EffectTrace should land with its four attach overloads intact so
catching wires in without touching the carrier again; until then the two frame-shaped overloads are dormant.

## Verification plan

Tests (proto test suite, all asserting on rendered content, not just non-emptiness):
- a throw inside a handler clause carries the suspension's frame innermost, then the pending maps, then the
  handle region label;
- a throw inside a trailing map after an answered operation (settle boundary) leads with the suspension;
- unhandled suspension's bug message arrives enriched;
- nested drives accumulate (inner elements before outer);
- a captured continuation (`Arrow.Eval` segment) rethrown in a later drive contributes its segment frames;
- NoStackTrace values keep their stack untouched but carry the readable getMessage;
- depth past the cap reports `... N more not walked`;
- fatal errors pass through unmodified.

Benchmarks to guard after the Suspend.frame addition: suspensionBaseline, suspensionFusesContinuation,
handleLoopAnswersInPlace (runtime; the accessor is dead weight unless thrown through, so expect noise-level),
and the SuspendSites / HandleSites compile fixtures (each suspend anon gains one `def`).

## Open questions

1. Should `Bind.value` contribute when it holds a pending computation? Walking it shows work that was parked
   but not yet started; kernel2's Defer case pushed only the cont. Recommend cont-only for parity, revisit if
   traces feel truncated.
2. Region labels use `Tag.show`; for the proto's interned-string tags this renders the type name, which is
   right, but worth eyeballing in the first rendered trace.
3. Where `Eval.apply` itself should splice for values that never entered the loop (a throw during the initial
   `cur` match): the loop-level catch already covers it since the match is inside the try.
