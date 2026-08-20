# kyo-kernel vs kyo-kernel2: divergence report

Read at `HEAD = 68793ec385 [kernel2] Effect.catching`, working tree clean. Old kernel `kyo/proto`,
`kyo/proto2`, `kyo/proto3` excluded; `kyo-kernel2` has no `proto` directory any more (the prototype was
promoted into `kyo/kernel`).

Two in-flight items, per the brief:

- `Effect.catching` **exists** and was never flagged missing here: `Effect.scala:66-71`, node
  `Kyo.Catching` at `KyoInternal.scala:69-76`, `Recover` at `Eval.scala:76-110`, consulted by
  `Stack.unwind` at `Stack.scala:233-238`. It is reported below as a signature change, not a gap.
- `ArrowEffect.handleCatching` **was** flagged absent. At this commit it is still only a commented
  signature (`ArrowEffect.scala:282-289`); treat that finding as superseded by the landing work. The one
  thing to carry forward: the old signature has `done: A => B < S3` and `accept: [C] => I[C] => Boolean`
  defaults (`kyo-kernel/.../ArrowEffect.scala:572-573`) that the parked kernel2 signature drops, and the
  sole consumer (`kyo-prelude/.../Abort.scala:202`) is shaped around them.

## File cross-reference

| OLD | NEW |
|---|---|
| `kyo/Kyo.scala` | `kyo/Kyo.scala` |
| `kyo/kernel.scala` | `kyo/kernel.scala` |
| `kyo/kernel/Effect.scala` | `kyo/kernel/Effect.scala` |
| `kyo/kernel/ArrowEffect.scala` | `kyo/kernel/ArrowEffect.scala` |
| `kyo/kernel/Loop.scala` | `kyo/kernel/Loop.scala` |
| `kyo/kernel/Pending.scala` | `kyo/kernel/Pending.scala` + `kernel/internal/Implicits.scala` |
| `kyo/kernel/ContextEffect.scala` | **no counterpart** |
| `kyo/kernel/Isolate.scala` | **no counterpart** |
| `kernel/internal/KyoInternal.scala` | `KyoInternal.scala` + `Nested.scala` + `Handler.scala` (+ `Eval.scala`, `Stack.scala`) |
| `kernel/internal/Safepoint.scala` | `kernel/internal/Safepoint.scala` (different machine) |
| `kernel/internal/Trace.scala` | `kernel/internal/EffectTrace.scala` (different mechanism) |
| `{jvm,native,js-wasm}/kernel/internal/TracePool.scala` | **no counterpart** |
| `kernel/internal/Context.scala` | **no counterpart** |
| `kernel/internal/CanLift.scala` | `kernel/internal/CanLift.scala` |
| `kernel/internal/LiftMacro.scala` | folded into `CanLift.scala` (`object LiftMacro`) |
| `kernel/internal/package.scala` | dissolved (`Eval` type members, `EffectTrace` private aliases) |
| — | `kyo/Arrow.scala` (kernel2-only, public) |

---

# 1. Per-file divergences

## `kyo/kernel.scala`

- **Absent:** `export kernel.Isolate` (OLD `:7`).
- **Absent:** `extension (self: kyo.bug.type) private[kyo] def failTag[A, B, S](kyo: A < S, expected: Tag[B]): Nothing`
  (OLD `:29-35`). Unhandled-effect failure is now `bug(s"unhandled suspension: ${kyo.tag}")` (`Eval.scala:210`),
  so the message text changes too.
- `type <`, `val Loop`, `type Id`, `type Const` identical (OLD `:3,5,17,27` / NEW `:3,5,15,25`).

## `kyo/Kyo.scala`

- **Nothing absent or added.** A declaration-level diff of both files matches one-for-one.
- **Reshaped:** every combinator dropped `Safepoint ?=>` on its callback and the `safepoint: Safepoint`
  using-parameter, e.g. OLD `def foreach[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: Safepoint ?=> A => B < S)`
  → NEW `(f: A => B < S)`. Uniform across `foreach`, `foreachConcat`, `foreachIndexed`, `foreachDiscard`,
  `filter`, `filterKeys`, `foldLeft`, `collect`, `findFirst`, `takeWhile`, `span`, `dropWhile`, `partition`,
  `partitionMap`, `scanLeft`, `groupBy`, `groupMap`, `fill`, for the `CC` / `List` / `Seq` / `Chunk` / `Set`
  / `Map` families. `Kyo.lift` unchanged (OLD `:33` / NEW `:35`).

## `kyo/kernel/Effect.scala`

- **Absent:** `detach` — in OLD it is `Isolate.internal.runDetached`; recorded as a commented gap at NEW
  `:77`, reason `:73-76` ("Waits on ContextEffect, which this kernel does not have").
- **Reshaped:** `catching` OLD `:37-39` → NEW `:66-68`, identical minus `safepoint: Safepoint`.
- **Reshaped:** `defer` OLD `:64` `(f: Safepoint ?=> A < S)(using Frame)` → NEW `:19` `(f: => A < S)(using Frame)`.
- **Reshaped:** `deferInline` OLD `:68` `(inline f: Safepoint ?=> A < S)(using inline _frame: Frame)` → NEW
  `:25` `(inline f: => A < S)` — **the `Frame` is dropped**, and `Kyo.Defer` has no `frame` member at all
  (`KyoInternal.scala:19-25`) where OLD's `KyoDefer` inherited one (`KyoInternal.scala:48,85`).
- **Semantic:** OLD evaluates the guarded body eagerly at the call site inside the `try` (`:56`); NEW defers
  it to the evaluator (`:69-70`, read at `Eval.scala:203`). The pure prefix of `catching { ... }` now runs later.
- **Semantic:** recovery moved from re-wrapping every continuation (OLD `:41-53`) to a stack entry found on
  unwind (`Recover`, `Eval.scala:76-110`; `Stack.unwind`, `Stack.scala:233`). Both decline fatal errors
  (OLD `:49,58`; NEW `Eval.scala:99-100`).
- **kernel2-only:** public `@static def defer(v, next)` `:31`, `@static def defer(v, a, b)` `:37`,
  `inline def bracket` `:90-92`.
- **Stale:** the scaladoc at `:46-64` still frames `catching` as an unimplemented gap directly above the
  working implementation; the banner at `:46-48` also covers `bracket`, also implemented.

## `kyo/kernel/ArrowEffect.scala`

Largest reshape. NEW keeps a block of commented signatures at `:250-300` headed *"Surface the previous
kernels carry that this one does not yet. Kept as signatures so the gap is visible here rather than only in
a parked test."*

**Absent**

- 2-effect `handle` — OLD `:165-176`. No NEW equivalent, no comment records it.
- 3-effect `handle` — OLD `:224-240`. Absent.
- 4-effect `handle` — OLD `:298-316`. Absent.
- `handleFirst` — OLD `:372-379`
  (`inline def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](effectTag: Tag[E], v: A < (E & S))(inline handle: [C] => (I[C], O[C] => A < (E & S)) => B < S2, inline done: A => B < S2)(using inline _frame: Frame, safepoint: Safepoint): B < (S & S2)`).
  Commented NEW `:259-265`; reason `:253-257`: *"handleCont keeps the region installed, and handleLoopState
  answers with a value rather than handing the clause a continuation it can end the region from."* Tests
  parked `ArrowEffectTest.scala:875-1055`.
- `dispatchFirst` — OLD `:409-414`. Commented NEW `:271-276`; reason `:267-270`: *"Parked with the IOTask
  integration design, which is what reads a standing operation."* Tests parked `ArrowEffectTest.scala:1057-1124`.
- `handlePartial` — OLD `:621-634`, **two tags plus an explicit `Context` argument and a `stop` thunk**.
  Commented NEW `:295-300` as a one-tag `Maybe`-returning form; reason `:291-293`. Tests parked
  `ArrowEffectTest.scala:1230-1340`. The stop half is now `Eval.partial` (`Eval.scala:146`), `private[kyo]`
  in `internal` and typed `A < Any`.
- `handleCatching` — OLD `:567-579`. Commented NEW `:282-289` in a narrower shape (no `done`, no `accept`).
  Tests parked `ArrowEffectTest.scala:1126-1228`. **Landing per the brief.**

**Renamed / reshaped**

| OLD | NEW |
|---|---|
| `handle` (1 effect) `:118-127` | `handleCont` `:58-64` |
| `handleLoop` (stateless) `:439-448` | `handleLoop` `:82-88` |
| `handleLoop` (stateful, no done) `:493-503` | `handleLoopState` `:132-138` |
| `handleLoop` (stateful + done) `:530-541` | `handleLoopState` `:106-113` |

- `handle` → `handleCont`: continuation is now an `Arrow[O[C], A, E & S]` rather than a function, a
  mandatory `done: A => B < S` is added (OLD had none, the region always ended at `A`), and **`S2` is gone**
  — the clause can no longer introduce effects outside `E & S`.
- `handleLoop` stateless: the clause loses the continuation entirely and continues on the answer.
  OLD `inline handle: [C] => (I[C], Safepoint ?=> O[C] => A < (E & S)) => Loop.Outcome[A < (E & S), A] < S2`
  → NEW `inline handle: [C] => I[C] => Loop.Outcome[O[C] < (E & S), B] < S` plus `inline done: A => B < S`.
- `handleLoop` stateful → `handleLoopState`: clause args inverted from `(I[C], State, cont)` to
  `(State, I[C])`, continuation dropped, `S2` gone.
- `suspend` OLD `:59-64` → NEW `:24-29`: type param `A`→`C`, parameter `funcionInput`→`effectInput`
  (OLD typo fixed).
- `suspendWith` OLD `:85-92` → NEW `:37-44`: `f` loses `Safepoint ?=>`; return row written `B < (E & S)`
  instead of `B < (S & E)`.
- `abstract class ArrowEffect[-Input[_], +Output[_]]` (OLD `:43`) → `ArrowEffect[I[_], O[_]]` (NEW `:19`):
  **variance dropped on both constructors**.

**Semantic**

- Handling is now a lazy value: `handleCont` builds a `Kyo.Handle` node (`:68`) answered only at `Eval`
  (`Eval.scala:204-320`), with resolution via `stack.find(kyo.tag)` (`Eval.scala:206`, `Stack.scala:99`),
  instead of OLD's per-call interpretive loops (`:129,178,242,318`). A settled input short-circuits to
  `done` with no node (`:77,101,127`).
- `suspendWith`'s node is its own continuation (NEW `:45-54` mixes `Transform` onto the `Suspend`); OLD
  allocated a `KyoSuspend` whose `apply` invoked `f` (`:93-101`).

**kernel2-only:** `handleContWith` `:147-157`, `handleLoopWith` `:181-191`, `handleLoopStateWith` `:215-226`
(rationale at `:141-144`).

## `kyo/kernel/ContextEffect.scala` — entire file absent

No counterpart for any member:

- `abstract class ContextEffect[+A] extends Effect` `:25`
- `trait Noninheritable { self: ContextEffect[?] => }` `:34-35`
- `inline def suspend[A, E <: ContextEffect[A]](inline effectTag: Tag[E])(using inline frame: Frame): A < E` `:46`
- `inline def suspendWith[A, E <: ContextEffect[A], B, S](inline effectTag: Tag[E])(inline f: Safepoint ?=> A => B < S)(using inline frame: Frame): B < (E & S)` `:59-63`
- `inline def suspend[A, E <: ContextEffect[A]](inline effectTag: Tag[E], inline default: => A)(using inline frame: Frame): A < Any` `:77-80`
- `inline def suspendWith[A, E <: ContextEffect[A], B, S](inline effectTag: Tag[E], inline default: => A)(inline f: Safepoint ?=> A => B < S)(using inline _frame: Frame): B < S` `:97-102`
- `inline def handle[A, E <: ContextEffect[A], B, S](inline effectTag: Tag[E], inline value: A)(v: B < (E & S))(using inline _frame: Frame): B < S` `:124-127`
- `inline def handle[A, E <: ContextEffect[A], B, S](inline effectTag: Tag[E], inline ifUndefined: A, inline ifDefined: A => A)(v: B < (E & S))(using inline _frame: Frame): B < S` `:145-151`

Corroboration in kernel2: `PendingTest.scala:670-681` (*"Parked with the removal of ContextEffect from
kyo-kernel2. Restore against the replacement design"*), `EffectTest.scala:350-410`. Design record:
`threaded-context-design.md:1-8`; `contexteffect-optional-design.md:1` states the removal was outright.

## `kyo/kernel/Isolate.scala` — entire file absent

No NEW file, no parked signature block, and no kernel2 test mentions it.

- `abstract class Isolate[Remove, -Keep, -Restore]` `:80`; `type State` `:84`; `type Transform[_]` `:87`
- `def capture[A, S](f: State => A < S)(using Frame): A < (Remove & Keep & S)` `:99`
- `def isolate[A, S](state: State, v: A < (S & Remove))(using Frame): Transform[A] < (Keep & S)` `:113`
- `def restore[A, S](v: Transform[A] < S)(using Frame): A < (Restore & S)` `:125`
- `def nest[A, S](v: A < (Remove & S))(using Frame): A < Restore < (Remove & Keep & S)` `:140`
- `final def run[A, S](v: A < (S & Remove))(using Frame): A < (S & Remove & Keep & Restore)` `:155`
- `final def use[A](f: this.type ?=> A): A` `:174`
- `final def andThen[RM2, KP2, RS2](next: Isolate[RM2, KP2, RS2]): Isolate[Remove & RM2, Keep & KP2, Restore & RS2]` `:189`
- `def apply[Remove, Keep, Restore](using i: Isolate[...]): Isolate[...]` `:208`
- `inline def derive[Remove, Keep, Restore]: Isolate[Remove, Keep, Restore]` `:220`
- `inline given [Remove, Keep, Restore <: Remove]: Isolate[Remove, Keep, Restore]` `:223`
- `private[kyo] inline def internal.runDetached[A, S](inline f: (Trace, Context) => A < S)(using inline _frame: Frame): A < S` `:228`
- `inline def internal.restoring[Ctx, A, S](trace: Trace, interceptor: Safepoint.Interceptor)(inline v: => A < (Ctx & S))(using frame: Frame, safepoint: Safepoint): A < (Ctx & S)` `:234-239`
- `object internal.Identity extends Isolate[Any, Any, Any]` `:246`
- `def internal.deriveImpl[Remove: Type, Keep: Type, Restore: Type](using Quotes): Expr[Isolate[...]]` `:254`

## `kyo/kernel/Loop.scala`

- **Nothing absent.** `Continue1-4`, `Outcome1-4`, `continue` x5, `done` x5, `apply` x4, `indexed` x5,
  `foreach`, `repeat`, `forever`, `whileTrue` all present.
- **Reshaped:** callbacks lost `Safepoint ?=>` and the `safepoint` using-parameter (OLD
  `:244,410,541,568,598,622` / NEW `:249,398,518,548,574,596`).
- **Reshaped:** `Outcome`/`Outcome2`/`Outcome3`/`Outcome4` gained covariance in `O` — OLD
  `opaque type Outcome[A, O]` (`:83,94,107,122`) → NEW `opaque type Outcome[A, +O]` (same lines).
- **Reshaped:** `done` x5 became `inline` (OLD `:198,206,214,222,230` → NEW `:203,211,219,227,235`);
  `Continue._1` went `def` → `val` (OLD `:146` → NEW `:146`); OLD's lowercase type-param typo
  `continue[A, B, o]` (`:156`) fixed to `[A, B, O]` (NEW `:156`).
- No behavioural divergence found in the loop protocol itself.

## `kyo/kernel/Pending.scala`

- **Absent:** `extension [A, S](v: A < S) private[kyo] def unsafeGet: A` (OLD `:369`) — role now taken by the
  free function `Nested.unnest` (`Nested.scala:23`), which is not on the pending type.
- **Absent:** `implicit inline def liftAnyVal[A <: AnyVal, S](inline v: A): A < S` (OLD `:441`) and
  `implicit inline def liftUnit[S](inline v: Unit): Unit < S` (OLD `:443`) — subsumed by the `erasedValue`
  match inside `Implicits.lift` (`Implicits.scala:11-15`), so the overloads are gone.
- **Reshaped:** `map`/`flatMap`/`andThen`/`unit` dropped `Safepoint ?=>` and the using-parameter (OLD
  `:59,97,130,161` / NEW `:23,42,64,86`).
- **Reshaped:** `flatten` moved from `extension [A, S, S2](v: A < S < S2) def flatten(using _frame: Frame): A < (S & S2)`
  (OLD `:375-381`, non-inline) to `inline def flatten[B, S2](using ev: A <:< (B < S2), inline _frame: Frame): B < (S & S2)`
  on `A < S` (NEW `:108`).
- **Reshaped:** `eval` moved from `extension [A](inline v: A < Any) inline def eval(using inline frame: Frame): A`
  (OLD `:394,406`) to `inline def eval(using S =:= Any): A` (NEW `:280`) — **no `Frame` parameter**.
- **Reshaped:** `handle` arities 2-10 changed `f1` from by-value `A < S => B` (OLD `:217,232,248,265,283,297,312,328,345`)
  to by-name `(=> A < S) => B` (NEW `:134,140,147,160,175,192,211,232,255`). Arity 1 is by-name in both
  (OLD `:205` / NEW `:129`).
- **Reshaped:** `lift` from a macro picking Cast / Nested / defaultLift off the static type (OLD `:439`,
  `LiftMacro.scala:23-33`) to a `compiletime.erasedValue` match (NEW `Implicits.scala:10-15`).
  `abortCastUnit` and `liftPureFunction1-6` moved verbatim into `private[kernel] trait Implicits`, which
  `object <` extends (`Pending.scala:17`).
- **Semantic:** the pending union gained an arm. OLD `opaque type <[+A, -S] = A | Kyo[A, S]` (`:42`) with
  `Nested <: Kyo` (`KyoInternal.scala:33`); NEW `A | Kyo[A, S] | Nested[A]` (`:15`) with `Nested` no longer a
  `Kyo` (`Nested.scala:6`). `Render` gained a third case accordingly (`:309-315`).
- **Semantic:** stack-safety moved from a per-thread instance (`safepoint.enter(_frame, value)`, OLD `:78`)
  to a global slot counter (`Safepoint.get()` / `enter(slot)` / `exit(slot)`, NEW `:30-34`). `unit` gained
  the check (NEW `:96-99`) where OLD had none (`:166-175`).
- **Semantic:** `eval` no longer clears an interceptor or opens a fresh trace. OLD ran through
  `Safepoint.eval` (`Safepoint.scala:195-204`); NEW is a direct `Eval(...)` (`:281`) that saves and restores
  only the budget (`Eval.scala:386-387,431`).
- **kernel2-only:** `private[kyo] inline def finalizeResources: Unit` `:300-301` → `Eval.finalizeResources`
  (`Eval.scala:445`).

## `kernel/internal/CanLift.scala` (+ `LiftMacro.scala`)

- **Absent:** `object CanLift.unsafe { inline given bypass[A]: CanLift[A] = null }` (OLD `:63-70`) — the
  documented escape hatch; `export CanLiftMacro.derived` (OLD `:59`); `LiftMacro.liftMacro` (OLD
  `LiftMacro.scala:8`); `LiftMacro.defaultLift` (OLD `LiftMacro.scala:36-39`, role moved to `Nested.nest`,
  `Nested.scala:28-31`).
- **Reshaped:** `derived` gained a second `NotGiven[A <:< Singleton]` and stopped being a macro (OLD `:42`
  → NEW `:30`); `CanLiftMacro.liftImpl` → `checkImpl` reached via `checkSingleton` (NEW `:42-44`), with an
  added rejection for nested computations (`:52-53`); `given CanLift[Nothing]` is now named `nothing`
  (OLD `:61` → NEW `:36`).
- **kernel2-only:** `derivedCaseObject` `:32`, `derivedSingleton` `:34`. `object LiftMacro` now holds only
  `abortCastUnitMacro` (`:62`).

## `kernel/internal/Context.scala` — entire file absent

- `private[kyo] opaque type Context = Map[Tag[Any], AnyRef]` `:13`; `empty` `:17`; `isEmpty` `:20`;
  `contains` `:22`; `inherit` `:30`; `getOrElse` `:37`; `get` `:41`; `set` `:45`; `NoninheritableFlag` `:56-57`.
- The `context: Context` parameter threaded through every continuation (`KyoInternal.scala:59`) is gone from
  the node protocol.

## `kernel/internal/KyoInternal.scala`

- **Absent:** `KyoSuspend[...]` with `def apply(v: O[A], context: Context)(using Safepoint): B < S` (OLD
  `:38-64`); `KyoContinue` (OLD `:68-72`); `sealed trait Defer extends ArrowEffect[Const[Unit], Const[Unit]]`
  (OLD `:81`, so `Tag[Defer]` no longer exists); `KyoDefer` (OLD `:85-88`);
  `case class Nested[+A](v: A) extends Kyo[A, Any]` (OLD `:33`).
- **Reshaped:** `sealed abstract private[kernel] class Kyo[+A, -S] extends Serializable` (OLD `:28`) →
  `sealed abstract private[kyo] class Kyo[+A, -S]` (NEW `:14`) — visibility widened, `Serializable` dropped.
- **kernel2-only nodes:** `Kyo.Defer[A,B,+C,-S]` `:19`; `Kyo.Suspend[...]` with `cont: Arrow[O[A],B,S]` `:27`;
  `Kyo.Park` `:54`; `Kyo.Catching` `:69`; `Kyo.Handle` `:78`; bounded `render` `:90-103`.

## `kernel/internal/Safepoint.scala` — replacement, not a port

- **Absent:** `implicit def get: Safepoint` `:69`; `abstract private[kyo] class Interceptor` `:101-105`;
  `getInterceptor` `:50` / `setInterceptor` `:52`; `immediate` `:108-110`; `propagating` `:125-129`;
  **public** `sealed abstract class Ensure ... extends AtomicBoolean with Function1[Maybe[Error[Any]], Unit]`
  `:142-155`; `ensure` `:169-173`; `eval` `:195-197`; both `handle` overloads `:206-209,217-221`;
  `enrich` `:231`; `writeReplace` / `Restore` serialization `:56,66-67`; `State.hasInterceptor` `:92` /
  `State.withInterceptor` `:95`.
- **Reshaped:** `enter(frame, value): Boolean` `:30` → `@static def enter(slot: Slot): Boolean` NEW `:121`;
  `exit(): Unit` `:47` → `exit(slot: Slot): Unit` NEW `:129`; `opaque type State = Long` with packed
  depth/threadId/interceptor `:71-99` → `opaque type State = Int` with `DepthGuard`/`Armed` NEW `:24,50-74`;
  `final class Safepoint private ()` `:25` → `private[kyo] class Safepoint` NEW `:9` (empty marker).
- **Semantic:** depth bound went from platform-specific `maxStackDepth = kyo.internal.Platform.maxStackDepth`
  (`package.scala:5-7`) to a fixed `period()` of 512 (NEW `:41,54`).
- **Semantic:** preemption went from `Interceptor.enter` returning false (`:40`) to a `Stop` marker in the
  slot table (NEW `:147-177`), read at `resolve` (`:111-112`) and on the budget slow path (`:124-127`).
- **Semantic / platform:** NEW uses `thread.threadId` in shared code (`:79`). OLD deliberately uses
  `Thread.currentThread().getId()` with the deprecation suppressed and says why at `Safepoint.scala:32-36`:
  *"its replacement threadId() is absent from the Scala.js javalib and fails JS and Wasm linking (it
  type-checks against the JDK, then breaks at link) ... This is shared code, so it must link on every platform."*
- **Semantic:** finalizer registration moved from `Interceptor.addFinalizer`/`removeFinalizer` +
  `Safepoint.ensure` (`:102-103,169`) to `Stack.pushFinalizer`/`drainFinalizers` (`Stack.scala:244,282`),
  reachable only through `Effect.bracket`.
- **kernel2-only:** public `opaque type Slot >: Int` `:22`; public `save` `:132` / `restore` `:138`;
  `reset` `:141`, `arm` `:144`, `stop` `:147`, `consumeStopped` `:171`; tunables `period` `:41`,
  `slotCount` `:43`.

## `kernel/internal/Trace.scala` + three `TracePool.scala` — absent, different mechanism

- **Absent (`Trace.scala`):** `final private[kyo] class Trace(frames: Array[Frame], var index: Int)` `:22-25`;
  `Trace.init` `:29`; `Trace.saved()(using Safepoint): Trace` `:34`; `Trace.render(trace): String` `:99`;
  `abstract private[kernel] class Trace.Owner extends TracePool.Local` `:107` and its members `pushFrame`
  `:111`, `saveTrace` `:121`, `copyTrace` `:138`, `releaseTrace` `:145`, `withTrace` `:148`, `withNewTrace`
  `:181`, `enrich` `:190`.
- **Absent (`TracePool.scala`, all three platform copies):** the object and `Local.borrow/release/clear` —
  jvm `:26,37,58,69` (8192/32), native `:7-44` (8192/32), js-wasm `:5-35` (0/1024).
- **Replacement:** `EffectTrace.scala` — public `final class EffectTrace extends Exception` `:22` with
  `getMessage` `:28`; `attach(ex, node, next, stack)` `:57`; `attach(ex, entry, next, stack)` `:67`;
  `splice(ex)` `:97`.
- **Semantic:** OLD records eagerly into a 16-slot ring on every `enter` (`Safepoint.scala:43`,
  `maxTraceFrames` `package.scala:8`) and enriches at the throw site. NEW records nothing and reconstructs at
  the boundary from the pending node and the eval stack, capped at 64 (`EffectTrace.scala:16-18,45`). OLD's
  frames are execution history; NEW's are pending work.
- **Semantic:** OLD splices at a matched position (`Trace.scala:194-198`); NEW prepends (`:93-94,109`). OLD
  skips `NoStackTrace` entirely (`:191`); NEW keeps the carrier and skips only the splice (`:94-98`). NEW adds
  synthetic region labels `StackTraceElement(tag.show, "handle", null, -1)` (`:202-209`), which OLD had no
  concept of.

## `kernel/internal/package.scala` — absent

- `private[kernel] inline def maxStackDepth = kyo.internal.Platform.maxStackDepth` `:7` — nothing in kernel2
  consults `Platform`.
- `private[kernel] inline def maxTraceFrames = 16` `:8` — kernel2's cap is `EffectTrace.MaxFrames = 64` (`:45`).
- `private[kernel] type IX[_]` / `OX[_]` / `EX <: ArrowEffect[IX, OX]` `:10-12` — reappear as **public type
  members of `object Eval`** (`Eval.scala:114-120`, with `CX`, `AX`, `BX`, `StateX`) and privately again in
  `EffectTrace` (`:50-52`).

---

# 2. Gaps that block replacing kyo-kernel

Ordered by downstream dependency. Counts are main-source (`/main/`) call sites measured in this tree.

**Caveat:** `build.sbt:808` already points `kyo-prelude` at `kyo-kernel2`, and the stack above is mid-port —
some of it references APIs in *neither* kernel (`ContextEffect.runDetached` at `Fiber.scala:173,749,788,896`;
`Safepoint.beginSlice` at `IOTask.scala:118`; `kyo.kernel.Observe` at `Debug.scala:7`). These counts measure
required capability, not a currently-compiling baseline.

1. **`Isolate` (whole file, plus `export kernel.Isolate`).** 29 main files across 11 modules: `kyo-core`
   63 refs (`Async.scala`, `Clock.scala`, `Fiber.scala`, `KyoApp.scala`, `Stat.scala`,
   `StreamCoreExtensions.scala`), `kyo-flow` 17 (`Flow.scala`, `FlowEngine.scala`,
   `internal/FlowInterpreter.scala`, `internal/StoreInterpreter.scala`), `kyo-reactive-streams` 11 (5 files),
   `kyo-prelude` 11 (`Var.scala`, `Emit.scala`, `Memo.scala`, `Check.scala`), `kyo-ai` 11, `kyo-combinators`
   9 (`AsyncCombinators.scala`), plus `kyo-stm/STM.scala`, `kyo-caliban/Resolvers.scala`,
   `kyo-actor/Actor.scala`, `kyo-offheap/Memory.scala`, `kyo-aeron/Topic.scala`. A redesign, not a port: OLD
   is built on `Context` capture and re-install (`Isolate.scala:228`, `Context.scala:30`).

2. **`ArrowEffect.handleLoop` clause reshape.** 46 sites — 39 `kyo-prelude`, 5 `kyo-core`, 1 `kyo-http`,
   1 `kyo-combinators`. Every one passes the OLD clause `(input, state, cont)` and calls `cont(...)` inside
   `Loop.continue` (canonical example `kyo-prelude/shared/src/main/scala/kyo/Var.scala:147-159`). NEW's clause
   is `(State, I[C])` with no continuation and no `S2`. Not a missing symbol, but every site must be
   rewritten, and any site that genuinely needs the continuation has to move to `handleCont`.

3. **`ContextEffect` + `internal/Context`.** 6 main files, and three foundational effects are *defined* as
   subclasses rather than merely calling in: `kyo-prelude/Env.scala` (`ContextEffect[TypeMap[R]]`),
   `kyo-prelude/Local.scala` (`ContextEffect[Map[Local[?], AnyRef]]` + `ContextEffect.Noninheritable`),
   `kyo-core/Scope.scala` (`ContextEffect[Scope.Finalizer]`). Also `kyo-core/Fiber.scala:8` importing
   `kyo.kernel.internal.Context` directly, `kyo-core/scheduler/IOTask.scala`, `kyo-actor/Actor.scala`.

4. **`ArrowEffect.handleFirst`.** 15 sites in 6 `kyo-prelude` files: `Stream.scala:705,709,738`,
   `Poll.scala:170,214,219`, `Sink.scala:44,49,61,196,199`, `Pipe.scala:247,250`, `Emit.scala:202`,
   `Choice.scala:125`. These are the stream / pipe / sink fusion points.

5. **`ArrowEffect.handle` (1 effect) → `handleCont`.** 9 sites: `kyo-prelude/Stream.scala:585,607`,
   `Emit.scala:136,156`, `Choice.scala:99`, `Check.scala:77` (8 in prelude), plus 1 in `kyo-combinators`.
   Mostly mechanical (rename, add `done = a => a`); the `S2` removal is the real constraint for any clause
   that introduces effects outside `E & S`.

6. **Safepoint integration surface** (`Interceptor`, `Ensure`, `ensure`, `immediate`, `propagating`,
   `getInterceptor`/`setInterceptor`). `Ensure` is public API (`Safepoint.scala:142`). In-tree consumers:
   `kyo-core/scheduler/IOTask.scala:21,118` (holds `@volatile private var running: Safepoint`, calls a
   not-yet-existing `Safepoint.beginSlice(deadline)`), and `kyo-direct` importing `kyo.kernel.internal.Safepoint`
   in 2 files. kernel2 offers `Eval.partial` + `stop`/`consumeStopped` + `Stack` finalizers as the
   replacement, but `Ensure`'s `Maybe[Error[Any]] => Unit` finalizer protocol has no analogue
   (`Finalizer.run()` takes nothing, `Eval.scala:49`).

7. **Trace capability (`Trace`, `TracePool`, `Trace.Owner`).** No surviving symbol consumer, but it is what
   `kyo-prelude/debug/Debug.scala` and fiber tracing are built on, and `Debug.scala:7` imports
   `kyo.kernel.Observe`, absent from both kernels. `EffectTrace` covers exception enrichment on a different
   model; it does not cover "snapshot the current trace and restore it on another thread", the role of
   `Trace.saved()` (`Trace.scala:34`) and `Isolate.internal.restoring` (`Isolate.scala:234`). Entangled with
   item 1.

8. **`ArrowEffect.handleCatching`** — landing per the brief. 1 site, `kyo-prelude/Abort.scala:202`, but it is
   `Abort`'s core so it gates the whole error-handling effect. Confirm the landing signature covers the `done`
   (`:572`) and `accept` (`:573`) parameters the OLD signature carries, which the parked kernel2 signature
   (`:282-289`) drops and which the `Abort` site is shaped around.

9. **`ArrowEffect.handlePartial` (two-tag + `Context` + `stop`).** 1 site,
   `kyo-core/scheduler/IOTask.scala:74`. `Eval.partial` (`Eval.scala:146`) covers the stop-driven half but is
   `private[kyo]` in `internal` and takes `A < Any`, so a two-effect partial drive over an unhandled row is
   not expressible.

10. **`ArrowEffect.dispatchFirst`.** 2 sites, `kyo-core/scheduler/IOTask.scala:170,195` (interrupt
    propagation over a stalled remainder). Small and self-contained.

11. **`Effect.detach` / `Isolate.internal.runDetached`.** 4 sites, all `kyo-core/Fiber.scala:173,749,788,896`
    (spelled `ContextEffect.runDetached`). Blocked behind item 3.

12. **Cross-platform coverage.** `kyo-kernel2` cross-builds for JS, Native, and Wasm (`build.sbt:772-775`)
    with zero platform-specific main sources, while its shared `Safepoint` uses `thread.threadId`
    (`Safepoint.scala:79`) — the exact call OLD documents as breaking Scala.js and Wasm linking
    (`kyo-kernel/.../Safepoint.scala:32-36`). OLD shipped three platform-specific `TracePool`s. Only `jvm`
    and `jvm-native` tests exist.

13. **`Safepoint ?=>` removal, `Loop.Outcome` covariance, `handle` by-name `f1`.** No lost capability, but a
    source break at every downstream site that writes an explicit `Safepoint ?=>` callback or relies on
    `implicit def Safepoint.get` being in scope.

14. **No downstream consumers found; lowest priority.** Multi-effect `handle` (2/3/4 tags, OLD `:165,224,298`)
    — verified 0 sites by scanning every `ArrowEffect.handle(` argument list for more than one `Tag[`;
    `unsafeGet` (OLD `Pending.scala:369`); `liftAnyVal` / `liftUnit` (OLD `:441,443`); `bug.failTag`
    (OLD `kernel.scala:30`); `CanLift.unsafe.bypass` (OLD `CanLift.scala:69`);
    `LiftMacro.defaultLift` / `liftMacro`; `Safepoint.immediate` / `propagating`.

---

# 3. kernel2-only, and what looks wrong or unfinished

## Wrong or stale

- **`kyo-kernel2/CONTRIBUTING.md` describes a kernel that no longer exists.** It documents `Handlers.scala`,
  `Arrow.AndThen.step`, `Kyo.Handled` / `HandledState` / `HandledFirst`, and live `handleFirst` /
  `handlePartial` / `handleCatching` (`:15,16,23,24,79,94,116,146,158`). The tree has `Stack.scala`,
  `Arrow.Chain`, `Kyo.Handle`, and none of those three handlers. Its checklist (`:185`) and its
  "read `Arrow.AndThen.step` and `Effect.catching` before designing any new evaluation mechanism"
  instruction (`:146`) point at symbols that are gone.
- **`Effect.scala:46-64`** still frames `catching` as an unimplemented gap directly above the working
  implementation; the banner at `:46-48` also covers `bracket`, also implemented.
- **`EffectTraceTest.scala:330-346`** still says *"Effect.catching is not in this kernel yet"* and keeps its
  two catching-trace cases commented out. The tracing obligation `Effect.scala:61-63` explicitly assigns to
  `catching` (attach and splice before calling `f`, so a handler does not see frames in the carrier that are
  not in the stack trace) therefore has no live test.
- **`kernel-parity-gaps.md`** records `kernel/ContextEffect.scala` as *"parity"* (`:19`) and
  `internal/CanLift.scala` as *"deleted"* (`:26`) — both inverted relative to the tree. Its `Isolate` (`:23`),
  `Trace`/`TracePool` (`:25-26`), and platform-coverage (`:130-138`) rows still hold.
- **`kernel-test-removals.md:29-45`** records `handleFirst` restored, `handleCatching` live, and `handleWith`
  absent — all three contradicted by the tree.

## Unfinished, flagged in-source

- `EffectTrace.scala:21` — `TODO This should become the new KyoException. Analyze what we need and if there
  are blockers.`
- `Safepoint.scala:8` — `TODO this should be private[kernel]`, on a class that is `private[kyo]` and
  otherwise empty.
- `CanLift.scala:29` — `TODO do these need to be inlined? I worry about compilation time.`
- `Pending.scala:20` — `TODO check the impact in compile and runtime perf of marking self as inline here`,
  on the extension every user-facing combinator expands through.
- `EffectTrace.scala:120-124` documents a regression it calls unfixable: with `Eval.apply` inline, the
  plumbing filter at `:127` matched nothing at expanded sites. `Eval.apply` is no longer inline
  (`:124-131` explains why it was un-inlined), so that first filter entry may now be dead in the other
  direction. Worth a re-read.

## Public surface that probably should not be

- `Nested.unnest[A](v: Any): A` and `Nested.nest` are public methods on a public object
  (`Nested.scala:23,28`) while the class itself is `private[kyo]`. The rationale at `:8-10,20-22` (inline
  expansion reach) is real, but `unnest` takes `Any` and casts unconditionally, so it is an unguarded cast
  primitive on the public surface.
- `Safepoint.Slot`, `Safepoint.State`, and `get` / `enter` / `exit` / `save` / `restore` are all public
  `@static` (`Safepoint.scala:22,24,81,121,129,132,138`). `save` / `restore` hand out the ability to reset
  another computation's depth budget.
- `object Eval` exposes `IX` / `OX` / `EX` / `CX` / `AX` / `BX` / `StateX` as public type members
  (`Eval.scala:114-120`); these were `private[kernel]` erasure helpers in the old kernel (`package.scala:10-12`).
- `EffectTrace.attach` / `splice` are public (`:57,67,97`), justified at `:36-39` by `Eval.apply` being
  `inline`. `Eval.apply` is no longer inline (`:124-126`), so that justification no longer applies and the
  visibility can likely narrow.
- `Effect.defer(v, next)` and `Effect.defer(v, a, b)` (`:31,37`) are public with no scaladoc; they let a
  caller splice arbitrary arrows onto a computation.
- `Arrow.Bracket` (`Arrow.scala:106-118`) is public while `Arrow.Transform`, `TransformBase`, `Chain`, and
  `Id` are all `private[kyo]`. Inconsistent, and `Bracket.release: Arrow[A, Any, Any]` at row `Any` is the one
  escape hatch in the arrow hierarchy.

## Genuinely new and coherent

- `Kyo.Park` (`KyoInternal.scala:54-61`) + `Eval.partial` (`Eval.scala:146`) + `Stack.snapshotEntries` /
  `snapshotStates` / `snapshotFinalizers` / `restore` (`Stack.scala:166-231`): a preemption slice that hands
  back a resumable value carrying its regions, their states, and outstanding finalizers. No OLD counterpart.
- `Effect.bracket` (`Effect.scala:90`) + `Finalizer` (`Eval.scala:44`) + `Stack.pushFinalizer` /
  `drainFinalizers` (`Stack.scala:244,282`) + `Pending.finalizeResources` (`Pending.scala:300`).
  **One thing to check:** `Eval.finalizeResources` only matches a bare `Park` (`Eval.scala:446-454`), and its
  own doc says so (`:442-443`): *"A parked value that has been composed since holds its park inside a deferral
  where this cannot see it, so a holder that intends to finalize must keep what it was handed."* That is a
  silent resource leak for any holder that maps over a park before deciding to abandon it, and nothing in the
  type system prevents that.
- `kyo/Arrow.scala` as a whole: `sealed trait Arrow[-A, +B, -S]` `:14-31`, `Arrow.id` `:35`, `Arrow.apply`
  `:38`, `Arrow.recursive` `:57`, `Transform` `:75`, `TransformBase` `:95`, `Bracket` `:106`, `Chain` `:121`,
  `Id` `:162`. This is the architectural piece the whole rewrite turns on and has no OLD analogue.
