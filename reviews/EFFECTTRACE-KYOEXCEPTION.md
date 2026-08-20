# EffectTrace as KyoException: blocker analysis

Target of the analysis: the TODO at `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala:21`,
read as the owner states it: `EffectTrace` and `kyo.KyoException` become one type.

All `file:line` references below were opened against the worktree at
`/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus` during this analysis.

## 1. Verdict

Achievable, and the merged type is a genuine improvement, but only under one design decision that the literal
reading of the goal gets backwards: **the effect frames must land in `getStackTrace`, not in `getMessage`.**
`KyoException` extends `NoStackTrace` (`kyo-data/shared/src/main/scala/kyo/KyoException.scala:26`), which means
its stack-trace slot is permanently empty and, verified below, still writable. That empty slot is exactly the
shape the effect trace wants, and filling it costs nothing that anyone currently reads. `getMessage`, by
contrast, is load-bearing across the repo in ways that a 64-line trace breaks outright: `kyo-test` compares it
for string equality (`kyo-test/api/shared/src/main/scala/kyo/test/internal/Intercept.scala:41,63`) and `kyo-http`
writes it into HTTP response bodies
(`kyo-http/shared/src/main/scala/kyo/internal/server/UnsafeServerDispatch.scala:879-882`). Given that split, the
merge is real: the trace state moves onto `KyoException` in kyo-data, `carrierOf` collapses to a type test for
every `KyoException` failure, the suppressed-carrier allocation disappears on that path, and the pinning test
that currently refuses to splice `NoStackTrace` survives verbatim because its subject is not a `KyoException`.
The costs are three: the suppressed-carrier mechanism does **not** go away (most throwables crossing a drive are
not `KyoException`, so the merge adds a path rather than removing one), the carrier role needs an explicit
marker bit because "a suppressed `KyoException`" stops being a reliable signal once `KyoException` is the common
public error base, and three fields land on a class that is allocated far more often than it is thrown, because
`Abort.fail` does not throw (`kyo-prelude/shared/src/main/scala/kyo/Abort.scala:57-71`). Nothing here is fatal.
The single hardest item is blocker 2.

## 2. Blockers

### B1. `NoStackTrace`: a cost and a rule inversion, not a technical block

**Claim under test.** `KyoException` extends `Exception with NoStackTrace`
(`kyo-data/shared/src/main/scala/kyo/KyoException.scala:26`); `EffectTrace.splice` refuses to touch a
`NoStackTrace` value (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala:98`), and a test
pins that refusal (`kyo-kernel2/shared/src/test/scala/kyo/kernel/internal/EffectTraceTest.scala:102-109`). So
every `KyoException` is currently exactly the class the splice will not write to.

**Evidence.**

- The guard: `if NonFatal(ex) && !ex.isInstanceOf[NoStackTrace] then`
  (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala:98`), with the reason stated at
  `:94-96`: "`NoStackTrace` keeps its carrier and skips the splice: the frames stay readable as data on a value
  that deliberately has no stack."
- The rule is older than this kernel. The shipping kernel's enrichment does the same:
  `if !ex.isInstanceOf[NoStackTrace] then`
  (`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Trace.scala:191`). So today, across both kernels, **no
  `KyoException` has ever received effect frames.** The merge inverts a rule that has held everywhere.
- Whether the inversion is even mechanically possible was not obvious, so I verified it rather than reasoned
  about it. `scala.util.control.NoStackTrace` overrides only `fillInStackTrace`, returning `this` unless a
  global flag is set (`javap -c scala.util.control.NoStackTrace` against
  `~/.ivy2/cache/org.scala-lang/scala-library/jars/scala-library-2.13.13.jar`: the default method tests
  `NoStackTrace$.noSuppression()` and otherwise returns `aload_0`). It does **not** pass
  `writableStackTrace=false` to the `Throwable` constructor. A JVM probe (a `NoStackTrace`-shaped Java class,
  compiled and run in the scratchpad) reports: fresh `getStackTrace` length 0, and after `setStackTrace` of a
  one-element synthetic array, length 1 with the element read back intact. The same probe confirms the contrast
  that matters for the design: `new Exception(null, null, false, false)`, which is the constructor `EffectTrace`
  uses today (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala:22`), **silently ignores**
  `setStackTrace` and still reports length 0.

**Fatal or cost.** Cost, and a small one. Two consequences:

1. The merged type must keep `KyoException`'s constructor (`extends Exception with NoStackTrace`) and must not
   inherit `EffectTrace`'s `Exception(null, null, false, false)` spelling. Taking the latter would make the whole
   splice a silent no-op on the merged type, verified above. This is the one place where a wrong mechanical
   choice produces code that compiles, passes a carrier-shaped test, and renders nothing.
2. The splice predicate stops being "is this `NoStackTrace`" and becomes "is this a `KyoException`, else is this
   `NoStackTrace`". The pinned case at `EffectTraceTest.scala:102-109` uses
   `class Silent extends Exception with NoStackTrace` (`:103`), which is not a `KyoException`, so it survives
   verbatim. A new case has to state the inverse rule for the merged type.

**What has to change.** `EffectTrace.splice` (`:97-113`) grows a `KyoException` arm ahead of the `NoStackTrace`
test. Nothing about `NoStackTrace` itself changes, and in particular the allocation property that motivated it
(no stack fill) is untouched, because `fillInStackTrace` is still the no-op.

### B2. `getMessage` across the subclasses: fatal if the trace goes there

**Claim under test.** `KyoException.getMessage` is environment-aware and already renders a frame
(`kyo-data/shared/src/main/scala/kyo/KyoException.scala:46-61`). If it also renders an effect trace, what breaks?

**Evidence.** The `kyo-test` warning the brief names is real and is the mild case:

- `kyo-test/api/shared/src/main/scala/kyo/test/Assertion.scala:21-23`: "This class intentionally extends
  RuntimeException (not KyoException). ConsoleReporter reads getMessage() verbatim to render the power-assert
  diagram. KyoException's environment-aware getMessage() would prepend frame-rendering text, corrupting the
  multi-line diagram layout." That is a documented *avoidance*, so it costs nothing further.

The cases that actually break are the ones that consume a `KyoException`'s `getMessage` directly:

- **Exact string equality.** `kyo-test/api/shared/src/main/scala/kyo/test/internal/Intercept.scala:41` and `:63`
  both do `if e.getMessage != msg then wrongMessage(msg, e.getMessage, t)`. `interceptMessage` and
  `interceptThrownMessage` are the repo-wide helpers for asserting on a thrown message. Any `KyoException` that
  crossed a drive before being intercepted would no longer compare equal. Every existing call site becomes a
  false failure.
- **Message written to the network.**
  `kyo-http/shared/src/main/scala/kyo/internal/server/UnsafeServerDispatch.scala:879-882`:
  `case e: HttpException => e.getMessage`, fed to `RouteUtil.encodeErrorBodyWithMessage(status, message)` at
  `:883`. `HttpException` extends `KyoException` (`kyo-http/shared/src/main/scala/kyo/HttpException.scala:29`),
  so an effect trace in `getMessage` becomes an HTTP error-response body. Same shape at
  `kyo-http/shared/src/main/scala/kyo/FlagAdmin.scala:103` (`errorResp(HttpStatus.BadRequest, e.getMessage)`).
- **Message embedded in another exception's message, compounding.**
  `kyo-http/shared/src/main/scala/kyo/HttpException.scala:280,309,346` each build a new `HttpException` whose
  message is `cause.getMessage`. `kyo-core/shared/src/main/scala/kyo/FileException.scala:101` does
  `s"I/O error on $path: ${cause.getMessage}"`.
  `kyo-test/prop/shared/src/main/scala/kyo/test/prop/PropertyFailedException.scala:41` inlines
  `${cause.getMessage}` into its own multi-line message.
- **Message as the type's `Render`.** `kyo-core/shared/src/main/scala/kyo/FileException.scala:106` and
  `kyo-core/shared/src/main/scala/kyo/CommandException.scala:59` both define
  `def asString(value: X): String = value.getMessage`. So `Render` of these types would print the effect trace.
- **A size limit that the trace can blow.** `KyoException` caps only the *cause detail* at 1000 chars
  (`kyo-data/shared/src/main/scala/kyo/KyoException.scala:50-51`, `:66`). The trace is capped at 64 elements
  (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala:45`), which is 64 uncapped lines.
  `kyo-test/api/shared/src/main/scala/kyo/test/Assertion.scala:43-51` records that the Scala Native test
  interface ships the message via `DataOutputStream.writeUTF`, capped at 65535 bytes, which is why
  `AssertionFailed` bounds at 16384 chars. `TestCancelled`
  (`kyo-test/api/shared/src/main/scala/kyo/test/Assertion.scala:85`) and `SystemExitException`
  (`kyo-test/runner/shared/src/main/scala/kyo/test/runner/Cli.scala:15`) are `KyoException`s crossing that same
  channel with no such bound.

**Fatal or cost.** **Fatal as posed.** Rendering the effect trace from `KyoException.getMessage` cannot be done
without breaking `Intercept` and changing HTTP response bodies.

**What has to change.** The design decision: for the *failure* role, the frames go into `getStackTrace` and
`getMessage` is left byte-identical to today. That is not a compromise, it is the better answer: a stack trace
is where a reader looks for a call path, and `NoStackTrace` has left that slot free (B1). The *carrier* role,
which attaches to a foreign throwable and has no message of its own, keeps the trace-rendering `getMessage` that
`EffectTrace` has today (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala:28-32`), because
that is the only way a suppressed carrier surfaces anything under `printStackTrace`. So `getMessage` branches on
the role, which requires the marker from B4.

### B3. Module layering: workable, and it forces the type to split from the walk

**Claim under test.** `KyoException` is in kyo-data; `EffectTrace` is in kyo-kernel2, which depends on kyo-data
and not the reverse. Can the merged type live where kyo-data can see it while the kernel fills it?

**Evidence.**

- `build.sbt:696-700`: `kyo-data` depends on `kyo-stats-registry` only.
- `build.sbt:772-775`: `kyo-kernel2` depends on `kyo-data`.
- `build.sbt:712-715`: the old `kyo-kernel` also depends on `kyo-data`.
- Everything the trace *state* needs is already in kyo-data: `Array[StackTraceElement]` is `java.lang`, and
  `Maybe` is `kyo-data/shared/src/main/scala/kyo/Maybe.scala:12`. `Maybe[Array[X]]` is a single unboxed
  reference, because `Present` is itself opaque (`kyo-data/shared/src/main/scala/kyo/Maybe.scala:81`:
  `opaque type Present[+A] = A | PresentAbsent`).
- Everything the *walk* needs is kernel-side: `Kyo`, `Arrow`, `Stack`, `Handler`, `Nested`
  (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala:161-294`). `Tag`
  (`kyo-data/shared/src/main/scala/kyo/Tag.scala`) and `Frame`
  (`kyo-data/shared/src/main/scala/kyo/Frame.scala`) are the only two kyo-data types it touches.
- `Frame.internal` is `private[kyo]` (`kyo-data/shared/src/main/scala/kyo/Frame.scala:135`), so kyo-data can mint
  a carrier with it as the default `Frame`.

**Fatal or cost.** Cost, and it is the clean part of this change. State and rendering move to kyo-data; the
reconstruction walk stays in kyo-kernel2. The `EffectTrace` *object* stays public for the reason already recorded
at `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala:36-39` (`Eval.apply` is `inline`, so
every symbol the drive names must be reachable at expansion sites outside package `kyo`). The owner's ruling that
`EffectTrace` stays public therefore holds in this form: the symbol that stays public in
`kyo.kernel.internal` is the object; the class half moves to `kyo.KyoException`.

**The consequence the owner should weigh.** kyo-data is below both kernels, and the shipping stack is still on
the old one: `build.sbt:805-808` shows `kyo-prelude` as the only module on `kyo-kernel2`, so kyo-core and
everything above it compile against `kyo-kernel`, whose `enrich` skips `NoStackTrace` and therefore skips every
`KyoException` (`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Trace.scala:191`). After the merge the
fields exist on every `KyoException` in the repo, and nothing fills them anywhere except under a kernel2 drive,
until the migration reaches each module. Cost lands immediately, benefit lands per module. Teaching the old
kernel's `Trace.Owner.enrich` (`:190-201`) to fill the same fields is a separate piece of work, not part of this
one.

### B4. Two mechanisms, not one, and the carrier's marker role collapses

**Claim under test.** Most throwables crossing a drive are not `KyoException`. If the trace lives on
`KyoException`, does the suppressed carrier survive for everything else?

**Evidence.**

- It has to. The identity of the failing value is pinned: `assert(ex eq boom)`
  (`kyo-kernel2/shared/src/test/scala/kyo/kernel/internal/EffectTraceTest.scala:62`). Wrapping a foreign
  throwable in a `KyoException` would change what a user's `catch` clause sees, so the carrier stays.
- The foreign case is not an edge case in the corpus. `class Boom extends RuntimeException("boom")`
  (`EffectTraceTest.scala:40`) is the subject of most cases; the unhandled-suspension case
  (`EffectTraceTest.scala:81-87`) travels a `bug.KyoBugException`, which is
  `case class KyoBugException(msg: String) extends Exception(msg)`
  (`kyo-data/shared/src/main/scala/kyo/data.scala:14`), a plain `Exception`, not a `KyoException`.
- So the merge **adds a path**. Today there is one mechanism (`carrierOf` at
  `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala:150-156` always allocates a carrier).
  After the merge there are two: self-carrier for a `KyoException`, suppressed carrier for everything else.
- The sharper problem: today the carrier's *type* is the marker. `find` scans `getSuppressed` for
  `case carrier: EffectTrace` (`EffectTrace.scala:139-148`), and the doc at `:18` says so explicitly: "its
  presence among `getSuppressed` marks an exception as already enriched". `EffectTrace` is a kernel-private
  shape that nobody else ever attaches. `KyoException` is the opposite: it is the public error base for 31
  direct subclasses. A suppressed `KyoException` occurs for unrelated reasons. Two real sites:
  `kyo-core/native/src/main/scala/kyo/internal/UUIDEntropyPlatformSpecific.scala:95`
  (`if failure ne closeFailure then failure.addSuppressed(closeFailure)`) and
  `kyo-compat/bindings/ox/jvm/src/main/scala/kyo/compat/CIO.scala:88` (`useT.addSuppressed(relT)`), the latter
  documented at `:18` and `:70` as the bracket release-failure convention. Under a naive merge, a
  `RuntimeException` carrying a suppressed `Closed` (`kyo-core/shared/src/main/scala/kyo/Closed.scala:5-7`, a
  `KyoException`) would be read as already-enriched, and the kernel would write effect frames into a genuine
  user error while leaving the actual failure undescribed.

**Fatal or cost.** Cost, but only if the marker is made explicit. Left implicit it is a silent correctness bug
that no current test would catch.

**What has to change.** The merged type carries a `private[kyo]` boolean set only by the kernel's carrier
factory, and `find` tests it. The honest way to state this to the owner: merging gives you one *class*, but the
class then needs a discriminator field, so what you get is a tagged union rather than a unification. That is the
irreducible cost of the merge, and it is small.

### B5. One creation `Frame` versus a sequence of effect frames: independent, with one redundancy

**Claim under test.** `KyoException` carries one `Frame` and renders it; the effect trace is a sequence.

**Evidence.**

- `KyoException` holds `(using val frame: Frame)` (`kyo-data/shared/src/main/scala/kyo/KyoException.scala:26`),
  and renders it in development mode via `frame.render(...)` (`:53-57`). The field is public and read outside
  kyo-data: `kyo-test/api/shared/src/test/scala/kyo/test/AssertionTest.scala:32` asserts
  `tc.frame.position.lineNumber > 0`.
- The trace elements are built from a different source: each is a `StackTraceElement` synthesized from a `Frame`
  the walk found on a node or an arrow
  (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala:189-199`), plus one region label per
  handler that carries no source position at all (`:202-209`).
- The two overlap by construction. A `KyoException` is allocated at the point the drive was running, so its
  creation `frame` is normally the same site as, or immediately adjacent to, the innermost element the walk
  emits. `Interrupted` makes this literal: it passes its own `at: Frame` as both the message text and the
  implicit frame (`kyo-core/shared/src/main/scala/kyo/Interrupted.scala:3-4`).

**Fatal or cost.** Neither. They are independent fields with different meanings: `frame` is where the error was
constructed, `traceElements` is the effect-level path the drive was on. Both stay. The only decision is
presentational: whether the innermost trace element should be suppressed when it equals the creation frame. My
recommendation is no, because the walk already collapses consecutive identical frames (`:189-199`, the
`(f ne last)` test) and the redundancy is one line.

### B6. Allocation: three fields on a class that is allocated far more often than it is thrown

**Claim under test.** `KyoException` is deliberately cheap and used heavily. What do three mutable fields cost,
and is there evidence about how hot the paths are?

**Evidence.**

- The class is cost-aware by construction: `getCause` is overridden with the comment "Overriding this method is
  important for performance since the method is synchronized in the super classes"
  (`kyo-data/shared/src/main/scala/kyo/KyoException.scala:28-30`), and `NoStackTrace` removes the stack fill
  (`:26`). `Closed` re-declares `with NoStackTrace` redundantly
  (`kyo-core/shared/src/main/scala/kyo/Closed.scala:7`), evidence that the no-stack property is treated as
  load-bearing at leaf level.
- The fields are three references' worth: an `Array[StackTraceElement]` (initialized to a shared empty array,
  the pattern already used at `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala:47`), an
  `Int`, and one `Maybe` which is a single unboxed reference (`kyo-data/shared/src/main/scala/kyo/Maybe.scala:81`).
  Plus the marker boolean from B4.
- **The decisive fact: most `KyoException` allocations never reach a drive's catch.** `Abort.fail` does not
  throw. `kyo-prelude/shared/src/main/scala/kyo/Abort.scala:57-58` routes to `error` at `:70-71`, which
  suspends with a `Result.Error` value. So a `Closed`, a `Timeout`
  (`kyo-core/shared/src/main/scala/kyo/Timeout.scala:3-4`), or an `Interrupted` raised through `Abort.fail`
  travels as data and never touches the trace fields. Allocation volume: 212 textual `Closed(` occurrences and
  24 `Interrupted(` occurrences in non-test sources.
- **On the throw path the merge is net cheaper, not more expensive.** For a `KyoException` that does reach
  `Eval`'s catch (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Eval.scala:209-215`), the merge removes
  the `new EffectTrace` allocation (`EffectTrace.scala:154`), removes `addSuppressed` (`:155`, which allocates
  and grows the suppressed list), and removes a `synchronized` `getSuppressed` plus its defensive array copy at
  each of the eight attach sites (`Eval.scala:52,62,70,113,170,179,188,197`, all routing through `reconstruct`
  at `EffectTrace.scala:80-89` into `carrierOf` at `:150-156`).
- **There is no instrument for the cost claim today.** The kernel's own standard is explicit that a performance
  result is a number and a named mechanism
  (`kyo-kernel2/.claude/skills/kernel/SKILL.md:153-157`) and that the instrument for an allocation question is
  `gc.alloc.rate.norm` (`SKILL.md:228-241`). No benchmark row in `kyo-kernel2/jvm/src/jmh/scala/kyo/kernel/bench/`
  allocates or throws an exception: a grep for `Exception|throw |Abort|fail` across `KernelBench.scala` and
  `ProtoKernelBench.scala` returns nothing.
- **A precedent that reads against the change, and why it does not apply.** `SKILL.md:490-491` records the
  project deleting a `suspended` variable that "existed only to enrich an exception at one boundary". That is
  state maintained by the *hot loop* for a cold consumer. This is state on the *exception*, paid only when an
  exception is allocated. The two trades are opposite in shape, and the precedent should be cited and answered
  rather than left for a reviewer to raise.

**Fatal or cost.** Cost, unquantified. Three fields on a class allocated on the `Abort.fail` path, which is the
dominant path and never uses them. The per-instance size delta is small and the throw path improves; whether the
net is positive depends on the allocation ratio between `Abort.fail` and actual throws, which nothing in the
repo measures.

**What has to change.** Per `SKILL.md:128-132` (a concession needs justification, minimal scope, a protective
measure, and a pinning test), a benchmark row that allocates a `KyoException` through `Abort.fail` in a loop has
to exist before the field addition is defensible, with `-prof gc` on both variants in the same session.

## 3. Checked and found not to be blockers

- **Constructor visibility.** `class KyoException private[kyo] (...)`
  (`kyo-data/shared/src/main/scala/kyo/KyoException.scala:23`). Every one of the 31 subclasses is in package
  `kyo` or a subpackage (`kyo.test`, `kyo.net`, `kyo.website`), so `private[kyo]` reaches them and a new
  `private[kyo]` carrier factory does not widen the public surface.
- **Binary compatibility.** `kyo-data` sets `.jvmSettings(mimaCheck(false))` (`build.sbt:707`), so MiMa does not
  gate a field addition.
- **Subclass source changes.** None are required. The trace state is added to the base with initializers; no
  constructor signature changes. This includes the awkward shapes:
  `case class ParseError(...) extends KyoException(...)` (`kyo-parse/shared/src/main/scala/kyo/ParseError.scala:3`),
  whose synthesized `equals` compares only its case fields and is unaffected by mutable base state; and
  `JsonRpcError`, which declares its own `val message: String` distinct from the base's by-name `message`
  (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala:30-36`).
- **Serialization.** `kyo-compiler`'s hand-written codec serializes leaf tags and typed fields, and renders a
  cause with `printStackTrace` into a string
  (`kyo-compiler/jvm/src/main/scala/kyo/CompilerException.scala:69-92`, `renderCause` at `:93-99`). It never
  reads the base's fields directly. A cause that carries effect frames renders them into that string, which is
  an improvement, not a break.
- **`getStackTrace` consumers.** Only two non-test readers exist repo-wide:
  `kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Worker.scala:416` (a live thread's stack, unrelated) and
  `kyo-core/shared/src/test/scala/kyo/FiberTest.scala:737`, which asserts the *presence* of enriched kyo frames
  (`frames.exists(_.getClassName.contains("@"))` at `:741`). Nothing asserts a `KyoException`'s stack trace is
  empty, so filling it breaks nothing that exists.
- **The `tracePhysical` cache does not collapse.** It is not an artifact of the carrier being a separate object;
  it is what stops a second boundary from re-filtering an already-rewritten trace
  (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala:102-108`, populated before
  `setStackTrace` at `:109`). After the merge it becomes *more* load-bearing, because splice now runs on
  `KyoException`s where it previously bailed at `:98`. Do not remove it.
- **Accumulation across nested drives.** Unaffected by moving the state to a field. `installInto` appends
  (`EffectTrace.scala:296-302`), and the pinning case
  (`EffectTraceTest.scala:89-100`, inner and outer regions ordered innermost first) works identically whether
  the array lives on a suppressed object or on the exception.
- **`ParseError`, `Interrupted`, `KyoApp.FailureException` as case classes.** Case-class equality is generated
  over the case fields only; base-class mutable state does not enter `equals` or `hashCode`.
- **`getSuppressed`-emptiness assertions.** Two exist
  (`EffectTraceTest.scala:131`, `:289`), both on `StackOverflowError`, which `NonFatal` excludes at
  `EffectTrace.scala:81` and `:98`. Unaffected.

## 4. The design

### 4.1 The merged type, in kyo-data

`kyo-data/shared/src/main/scala/kyo/KyoException.scala`, replacing the current file:

```scala
package kyo

import KyoException.maxMessageLength
import kyo.*
import kyo.Ansi.*
import kyo.internal.Environment
import scala.util.control.NoStackTrace

/** Kyo's base exception class ... (existing scaladoc, plus:)
  *
  * A failure that crosses an evaluation boundary carries the effect-level frames of the computation that raised
  * it. They are reconstructed at the boundary, not recorded while the computation runs, and they land in this
  * exception's own stack trace, which `NoStackTrace` leaves empty and writable. `getMessage` is unaffected: a
  * caller that compares or forwards the message sees the same string whether or not the failure crossed a drive.
  */
class KyoException private[kyo] (
    message: => String = "",
    cause: String | Throwable = "",
    // set only by KyoException.trace, for the instance the kernel attaches to a throwable that
    // cannot carry frames itself
    private[kyo] val isTrace: Boolean = false
)(using val frame: Frame) extends Exception with NoStackTrace:

    /** The reconstructed effect frames, innermost first. Empty until an evaluation boundary fills it. */
    private[kyo] var elements: Array[StackTraceElement] = KyoException.noElements

    /** Frames the walk's cap kept it from reaching, summed over every boundary crossed. */
    private[kyo] var dropped: Int = 0

    /** The physical trace with the kernel's plumbing removed, computed once so a second boundary does not
      * re-filter a trace the first one already rewrote.
      */
    private[kyo] var physical: Maybe[Array[StackTraceElement]] = Maybe.Absent

    override def getCause(): Throwable =
        cause match
            case cause: Throwable => cause
            case _                => null

    override def getMessage(): String =
        if isTrace then KyoException.renderTrace(this)
        else
            val detail =
                cause match
                    case t: Throwable =>
                        Maybe(s"${t.getClass.getSimpleName}: ${Maybe(t.getMessage).getOrElse("")}".take(maxMessageLength))
                    case cause: String @unchecked => Maybe(cause.take(maxMessageLength))

            if Environment.isDevelopment then
                val msg = frame.render(
                    ("⚠️ KyoException".red.bold :: message :: detail.toList)*
                )
                s"\n$msg\n"
            else
                message + detail.fold("")(" " + _)
            end if
    end getMessage
end KyoException

object KyoException:
    /** Maximum length for error messages to prevent excessive output. */
    val maxMessageLength = 1000

    private[kyo] val noElements = new Array[StackTraceElement](0)

    /** The instance an evaluator attaches, as a suppressed exception, to a throwable that is not a
      * `KyoException` and so has nowhere of its own to hold effect frames. It is a `KyoException` only so that
      * one type carries the frames; it is never a failure, and `isTrace` is what tells the two apart. The flag
      * is required rather than cosmetic: a suppressed `KyoException` also arises from the ordinary
      * release-failure convention, and a scan without it would mistake a real error for a carrier.
      */
    private[kyo] def trace(): KyoException =
        new KyoException("", "", isTrace = true)(using Frame.internal)

    private[kyo] def renderTrace(self: KyoException): String =
        val body = self.elements.iterator.map(e => s"at $e").mkString("\n")
        if self.dropped == 0 then s"effect trace:\n$body"
        else s"effect trace:\n$body\n... ${self.dropped} more not walked"
    end renderTrace
end KyoException
```

Two notes on this block. `isTrace` is a constructor parameter rather than a fourth `var` so that it is final and
cannot drift; it is the discriminator B4 requires. `renderTrace` lives in the companion rather than as a method
so that the instance surface of `KyoException` gains nothing public.

### 4.2 The kernel side

`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala` loses its class and keeps its object.
The three entry points (`attach` twice, `splice`) keep their names and signatures, so the eight call sites in
`Eval.scala` and the boundary call are untouched. The diff is confined to `carrierOf`, `find`, `splice`, and
`installInto`:

```scala
    /** Runs one reconstruction into the exception's trace state.
      *
      * (existing scaladoc for the fatal-error and walk-failure rules, unchanged)
      */
    private inline def reconstruct(ex: Throwable)(inline fill: Builder => Unit): Unit =
        if NonFatal(ex) then
            try
                val carrier = carrierOf(ex)
                val builder = new Builder(MaxFrames - carrier.elements.length)
                fill(builder)
                builder.installInto(carrier)
            catch case failure if NonFatal(failure) => ()
        end if
    end reconstruct

    /** Writes the accumulated frames into the exception's stack trace, synthesized frames first, then the
      * physical trace with the kernel's plumbing removed.
      *
      * A `KyoException` is its own carrier and is spliced even though it is `NoStackTrace`: its stack slot is
      * empty by construction and still writable, so the effect frames become the trace a reader sees, which is
      * the point of the two being one type. Any other `NoStackTrace` value keeps its carrier and its empty
      * stack: the frames stay readable as data on a value that deliberately has no stack.
      */
    def splice(ex: Throwable): Unit =
        if NonFatal(ex) then
            try
                val carrier =
                    ex match
                        case k: KyoException                    => Maybe(k)
                        case _ if ex.isInstanceOf[NoStackTrace] => Maybe.Absent
                        case _                                  => find(ex)
                carrier match
                    case Maybe.Present(carrier) if carrier.elements.length > 0 =>
                        val physical =
                            carrier.physical match
                                case Maybe.Present(p) => p
                                case Maybe.Absent =>
                                    val p = ex.getStackTrace.filterNot(isPlumbing)
                                    carrier.physical = Maybe(p)
                                    p
                        ex.setStackTrace(carrier.elements ++ physical)
                    case _ => ()
            catch case failure if NonFatal(failure) => ()
        end if
    end splice

    /** The suppressed carrier, if this throwable already has one. Only an instance the evaluator minted counts:
      * a suppressed `KyoException` is also what the release-failure convention attaches, and enriching one of
      * those would describe the wrong failure.
      */
    private def find(ex: Throwable): Maybe[KyoException] =
        val suppressed = ex.getSuppressed
        @tailrec def loop(i: Int): Maybe[KyoException] =
            if i == suppressed.length then Maybe.Absent
            else
                suppressed(i) match
                    case c: KyoException if c.isTrace => Maybe(c)
                    case _                            => loop(i + 1)
        loop(0)
    end find

    /** A `KyoException` holds its own frames. Anything else gets one attached. */
    private def carrierOf(ex: Throwable): KyoException =
        ex match
            case k: KyoException => k
            case _ =>
                find(ex) match
                    case Maybe.Present(carrier) => carrier
                    case Maybe.Absent =>
                        val carrier = KyoException.trace()
                        ex.addSuppressed(carrier)
                        carrier
```

and, in `Builder`, only the parameter type changes:

```scala
        def installInto(carrier: KyoException): Unit =
            if size > 0 then
                val fresh = out.slice(0, size)
                carrier.elements = if carrier.elements.length == 0 then fresh else carrier.elements ++ fresh
            end if
            carrier.dropped += dropped
        end installInto
```

Everything else in the file (the cap at `:45`, the `Item` worklist, the arm order at `:266-291` that F13 of
`reviews/EFFECTTRACE-PORT-REVIEW.md` established as load-bearing for termination, `isPlumbing` at `:125-137`) is
unchanged.

### 4.3 What the merge buys, stated as mechanisms

- For a `KyoException` failure, `carrierOf` is one `instanceof` instead of a `synchronized getSuppressed` plus a
  defensive array copy, at each of the eight attach sites.
- For a `KyoException` failure, no `EffectTrace` object is allocated and no suppressed list is created or grown.
- A `KyoException` that crossed a drive prints its effect frames as its stack trace, which is the first place a
  reader looks and the slot that was empty before.
- `getMessage` is unchanged for every existing subclass, so `Intercept`, the HTTP error bodies, and the `Render`
  instances keep working.

## 5. Fallout inventory

### Source files that change

| file | change |
|---|---|
| `kyo-data/shared/src/main/scala/kyo/KyoException.scala` | the merge target: 4 fields, an `isTrace` ctor param, a `getMessage` branch, a `trace()` factory, `renderTrace` |
| `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala` | class deleted, object kept; `carrierOf`, `find`, `splice`, `installInto`, `reconstruct` retyped |
| `kyo-test/api/shared/src/main/scala/kyo/test/Assertion.scala:21-23` | the standing warning has to be re-stated: it is still true (KyoException's `getMessage` still prepends frame text in development) but it now needs to say the effect trace is *not* in the message |

`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Eval.scala` does not change: the eight attach sites
(`:52,62,70,113,170,179,188,197`) and the boundary splice (`:214`) keep their spelling. The `TODO` at `:211`
about a second observation point, and the tracing contract `Effect.catching` owes
(`kyo-kernel2/shared/src/main/scala/kyo/kernel/Effect.scala:54-57`), are unaffected by the merge and remain open.

### Subclasses: 31 direct, across 20 modules, zero source changes required

All 31 change *behavior* (their `getStackTrace` can now be non-empty), none change *source*.

`kyo-aeron/.../TopicException.scala:24`, `kyo-ai/.../AIException.scala:24`,
`kyo-browser/.../BrowserException.scala:26`, `kyo-combinators/.../KyoCombinators.scala:12`,
`kyo-compiler/jvm/.../CompilerException.scala:19`, `kyo-core/jvm/.../StreamCompression.scala:14`,
`kyo-core/.../Admission.scala:13`, `kyo-core/.../Closed.scala:6`, `kyo-core/.../CommandException.scala:25`,
`kyo-core/.../FileException.scala:28`, `kyo-core/.../Interrupted.scala:4`, `kyo-core/.../KyoApp.scala:14`,
`kyo-core/.../Timeout.scala:4`, `kyo-data/.../Duration.scala:20`, `kyo-data/.../UUID.scala:88`,
`kyo-flow/.../FlowException.scala:23`, `kyo-http/.../HttpException.scala:29`,
`kyo-jsonrpc/.../JsonRpcError.scala:36`, `kyo-net/.../NetException.scala:22`, `kyo-parse/.../ParseError.scala:3`,
`kyo-pod/.../ContainerException.scala:43`, `kyo-schema/.../SchemaException.scala:10`,
`kyo-slack/.../SlackException.scala:13`, `kyo-stm/.../STM.scala:257`, `kyo-ui/.../UIException.scala:4`,
`kyo-website/.../WebsiteException.scala:12`, `kyo-workers/.../WorkersException.scala:3`,
`kyo-test/api/.../Assertion.scala:85`, `kyo-test/prop/.../Gen.scala:715`,
`kyo-test/prop/.../PropertyFailedException.scala:36`, `kyo-test/runner/.../Cli.scala:15`.

Beneath those 31 sit roughly 180 leaf classes in the sealed hierarchies (25 under `HttpException`, 23 under
`BrowserException`, 22 under `SchemaException`, 18 under `NetException`, 17 each under `AIException` and
`ContainerException`, 11 under `JsonRpcError`, 10 under `FlowException`, and smaller sets under the rest). They
inherit the behavior change and need no source edit.

### Tests

**`kyo-kernel2/shared/src/test/scala/kyo/kernel/internal/EffectTraceTest.scala`**

- `:31-32`, the `carrier` helper: `collectFirst { case t: EffectTrace => t }` becomes
  `collectFirst { case t: KyoException if t.isTrace => t }`. Mechanical, but it is the file's spine, so it
  touches every case that calls it. The test package is `kyo.kernel.internal`, inside `kyo`, so `isTrace`'s
  `private[kyo]` visibility reaches it.
- `:102-109`, "NoStackTrace keeps its carrier and its empty stack": **survives verbatim**, because its subject is
  `class Silent extends Exception with NoStackTrace` (`:103`), not a `KyoException`. Worth a comment saying so,
  since a reader will assume otherwise.
- `:322-328`, "the carrier renders the frames as a message": survives, because the failure is
  `Boom extends RuntimeException` (`:40`) and the carrier keeps the trace-rendering `getMessage`.
- `:340-346`, the commented case waiting on `Effect.catching`: its last assertion
  `ex.getSuppressed.count(_.isInstanceOf[EffectTrace]) == 1` becomes ambiguous and has to become
  `count { case k: KyoException => k.isTrace; case _ => false } == 1`.
- Unchanged: `:57-69`, `:71-79`, `:81-87` (the failure is `bug.KyoBugException`, a plain `Exception`),
  `:89-100`, `:111-124`, `:126-132`, `:134-178`, `:180-186`, `:188-211`, `:213-279`, `:281-291`, `:293-306`,
  `:308-320`.
- **New cases required.** Four, and the first two are the regression guards for B1 and B4:
  1. a `KyoException` failure crossing a drive carries its frames in its own `getStackTrace` and allocates no
     suppressed carrier (`ex.getSuppressed.isEmpty`);
  2. a foreign throwable that already carries an unrelated suppressed `KyoException` still gets its own carrier
     minted, and the unrelated one is not written into;
  3. a `KyoException` crossing two nested drives accumulates once and does not duplicate the frames the first
     splice wrote (the `physical` cache);
  4. `getMessage` of a `KyoException` that crossed a drive is byte-identical to one that did not.

  Case 1 asserts `getStackTrace` shape, which the project already treats as non-portable
  (`kyo-kernel2/jvm-native/src/test/scala/kyo/kernel/internal/EffectTracePhysicalTest.scala:8-12`), so it belongs
  in `jvm-native/src/test`, not the shared corpus.

**`kyo-kernel2/jvm-native/src/test/scala/kyo/kernel/internal/EffectTracePhysicalTest.scala`**

- `:21-22`, the `carrier` helper: same retyping as above.
- `:29-36`, `:38-43`, `:47-62`: unchanged, all three use `Boom extends RuntimeException` (`:24`).
- Add the `KyoException` self-carrier physical case (case 1 above).

**`kyo-data/shared/src/test/scala/kyo/KyoExceptionTest.scala`**

- `:5-30`, all four `getMessage` cases: unchanged under this design, which is the point.
- Add: a fresh `KyoException` has `elements.isEmpty`, `dropped == 0`, `physical == Absent`, and an empty
  `getStackTrace`; and `KyoException.trace()` renders `"effect trace:"` from `getMessage`. Note the friction:
  this file uses `kyo.test.Test[Any]` (`:3`) while the kernel2 corpus uses raw scalatest, because kyo-kernel2
  drops the kyo-test jars during the migration (`build.sbt:779-784`).

**`kyo-test/api/shared/src/test/scala/kyo/test/AssertionTest.scala`**

- `:21-26`, `:30-33`, `:37-46`: unchanged. The header comment at `:7-15` enumerates what the file covers and
  should gain the message-stability property (case 4 above) if the kyo-test side wants its own guard.

### Count

3 source files changed, 31 subclasses behaviorally affected across 20 modules with no source edits, roughly 180
leaf classes inheriting the change, 4 test files touched, 5 existing test cases retyped or restated, 5 new test
cases required, and 1 new benchmark row required before the field addition is defensible under
`SKILL.md:128-132`.

## 6. Questions only the owner can answer

1. **Is `getStackTrace`, not `getMessage`, the right home for the frames?** This is the design's load-bearing
   decision and it is the one place where the analysis pushes back on the literal goal. B2 shows `getMessage` is
   not viable. If the owner wants the trace in the message anyway, `Intercept.scala:41,63` and
   `UnsafeServerDispatch.scala:879-883` have to be redesigned first, and that is a different campaign.

2. **Is a discriminator field acceptable?** B4 shows the merged class needs one bit saying "I am a carrier, not a
   failure". That makes the result a tagged union rather than a unification. The alternative is a `private[kyo]`
   subclass of `KyoException` for the carrier role, which is honest about the two roles but reintroduces a
   distinct type and so is further from the stated goal. I recommend the flag; the owner should confirm they
   accept what it means.

3. **Does the old kernel get taught to fill the same fields, or do the fields lie dormant until the migration
   lands?** B3: `build.sbt:805-808` puts only kyo-prelude on kernel2 today, and the old kernel's `enrich` skips
   every `KyoException` (`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Trace.scala:191`). Until kyo-core
   migrates, the merge is all cost and no benefit for the shipping stack.

4. **Does the benchmark row get built before or after the merge?** B6: nothing measures `KyoException`
   allocation today, and `Abort.fail` never throws, so the fields are paid on the dominant path and used on the
   rare one. `SKILL.md:128-132` says a concession without a measurement is a defect. Building the row first is
   the conservative order; the owner may prefer to land the merge and measure the pair afterward.

5. **Should the carrier's `getMessage` survive at all, or should the carrier also be spliced?** Section 4 keeps
   the trace-rendering `getMessage` for the carrier role, a literal move of
   `EffectTrace.scala:28-32`. The alternative is to `setStackTrace` the carrier too, so the frames appear under
   the `Suppressed:` line as a real stack rather than as message text, and drop the `getMessage` branch entirely.
   That is cleaner and would let `isTrace` govern nothing but `find`, at the cost of one more `setStackTrace`
   per boundary and the deletion of `EffectTraceTest.scala:322-328`.

6. **Is the JS/Wasm behavior of `setStackTrace` verified anywhere?** The `NoStackTrace` writability in B1 is
   verified on the JVM only. `kyo-kernel2` cross-builds all four platforms (`build.sbt:772-773`), and the
   project's existing position is that physical-trace assertions are not portable
   (`EffectTracePhysicalTest.scala:8-12`). If a `KyoException`'s frames are only visible on JVM and Native, the
   merge's user-visible benefit is platform-dependent and should be stated in the scaladoc.
