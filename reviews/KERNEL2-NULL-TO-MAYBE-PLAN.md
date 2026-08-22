# Null-to-Maybe migration plan

User's instruction, 2026-08-22: "check where we use null in the kernel, we should migrate to Maybe
if there's no significant cost, let's do that as a follow up after this optimization". This is the
prepared plan; execution starts after the answersCont optimization lands. No source has been
touched for it yet.

## Ground facts

- Inventory: 50 null sites across 6 files (Stack, Eval, Handler, Safepoint, CanLift, EffectTrace),
  proto namespace excluded.
- Maybe's representation (kyo-data/Maybe.scala): `opaque type Maybe[+A] = Absent | Present[A]`,
  `Present[+A] = A | PresentAbsent`. For payload types that cannot themselves be Absent or a Maybe
  (Throwable, Arrow, Finalizer), reads and writes are sentinel comparisons: zero allocation, same
  cost class as `eq null`. For `Any`-typed payloads, a value that is itself Absent or a Maybe pays
  `PresentAbsent` wrapper traffic: correct, but a per-answer cost hazard on protocol lanes.
- Stack already models absence with Maybe where absence is the meaning: `states` and `finalizers`
  are Maybe arrays (`finalizers(pending) = Absent`).

## Classification and verdicts

### 1. Migrate (absence-modeling, cold path, payload cannot nest): the executable slice

**Stack.drainFinalizers and its caller.** The one genuine absence-modeled null crossing a method
boundary. Cold (finalizer drain, once per eval leaving with pending finalizers).

```scala
// Stack.scala:378, signature and locals
def drainFinalizers(failure: Maybe[Throwable]): Unit =
    if pending > 0 then
        var first: Maybe[Throwable] = Absent
        val outcome = Result.panic[Nothing, Nothing](failure.getOrElse(Finalizer.Abandoned))
        while pending > 0 do
            pending -= 1
            val f = finalizers(pending)
            finalizers(pending) = Absent
            try f.foreach(_.run(outcome))
            catch
                case ex: Throwable =>
                    failure match
                        case Present(fail) => fail.addSuppressed(ex)
                        case Absent =>
                            first match
                                case Present(fst) => fst.addSuppressed(ex)
                                case Absent       => first = ex
            end try
        end while
        if failure.isEmpty then first.foreach(throw _)
```

```scala
// Eval.scala:587 and :621, the caller's slot
var failure: Maybe[Throwable] = Absent
...
stack.drainFinalizers(failure)
```

Also the scaladoc line "or null if it is completing normally" becomes "or Absent". The `failure`
var is written on the recovery loop's catch path and read per eval, not per operation; the settled
floor row (evalFixedOverheadBatch, 1.6ns) is the row that would notice if this were wrong, so it
is the verification row.

**Stack.scala:282, the Park snapshot conversion.** `arr(i) = finalizers(i).getOrElse(null...)`
materializes a null-padded plain array from the Maybe array for the Park snapshot. Migrate the
snapshot to `Array[Maybe[Finalizer[?, ?]]]` filled with the Maybe values directly, and `restore`
reads them back without the null bridge. Cold (park path). Touches the Park node's field type and
`restore`; scope one screen.

### 2. Bracket-gated candidate (absence-modeling, hot protocol lane, payload cannot nest)

**Out.cont and the dispatcher tests** (`Handler.Out.cont: Arrow[Any, Any, Any] = null`;
`out.cont ne null` in Eval:189/193/204/232/327/332/346 and the template commits). This IS absence
modeling (consumed vs unconsumed continuation) and `Maybe[Arrow[Any, Any, Any]]` models it at the
same machine cost in principle (sentinel compare either way; Arrow cannot nest). It sits on the
per-answer protocol of every fast dispatcher, so per the concession discipline it moves only
behind a measured bracket: suspensionBaseline, statefulAnswersPaySuccessor, handleLoopAnswersInPlace,
evalFixedOverheadBatch, -f 3, must be inside the drift band. If the bracket is clean, migrate;
record the numbers either way.

### 3. Keep: not absence modeling at all

- **GC-clears**: `entries(i) = null` (Stack 135/210/245), `free(size) = null` (444). The live
  region is bounded by head/size; no read ever null-tests these slots (verified against every
  `entries(` read site). The null is object-graph hygiene for the collector, not a value.
  Wrapping would change nothing observable.
- **Definite-assignment locals in flag-driven loops**: `var result: Any = null` (Handler
  141/244/343), `var out = null.asInstanceOf[Any]` (Eval 599). Assigned on every exit path before
  read; inline bodies cannot early-return, which is why the flag shape exists. There is no absent
  case to model.

### 4. Keep: the null is the platform's or the design's

- **Safepoint** (89/103/165): the slots table is an AtomicReferenceArray whose unclaimed cells are
  null by platform initialization, and the claim/stop protocol CASes over that state; the source
  comment pins it ("no site writes a cell back to null once claimed"). `local.get()` null is
  ThreadLocal-without-initialValue. This is the kernel's hottest path (the Safepoint read is on
  every settled map) and the nulls are the lock-free protocol itself. Keep, concession documented.
- **CanLift** (30/32/36/55): the evidence values are deliberately null, erased compile-time
  tokens; the macro emits `null.asInstanceOf[CanLift[A]]` as the zero-cost evidence. That is the
  design, not an absence.
- **EffectTrace** (22/205): `Exception(null, null, false, false)` is the JVM idiom for
  no-message/no-cause/suppression-off, and `StackTraceElement(..., null, -1)` is the JVM API for
  unknown file. Platform API arguments.

### 5. Out.state / Out.input stay null-laned regardless

`Any`-typed lanes where the payload can be literally anything the user's clause produced,
including Absent or a Maybe: `Maybe[Any]` is correct but pays `PresentAbsent` wrapper traffic per
answer exactly when user state is a Maybe, a data-dependent cost cliff on the hottest protocol.
The cell is private[kyo], never escapes, and documented; the protection stands in for the type.

## Execution order (after answersCont lands)

1. Slice 1: drainFinalizers + Eval failure slot + scaladoc; suite green; evalFixedOverheadBatch
   and deepRecursionPaysRescuesOnly spot-check (-f 1), full board only if anything moves.
2. Slice 2: Park snapshot Maybe array; suite green (Park pins cover restore).
3. Bracket: Out.cont Maybe experiment on a branch; -f 3 on the four rows above; adopt or record
   and drop per the numbers.
4. The keeps get their justification comments only where one is missing today; no code motion.
