# Cross-library benchmark plan

Implementation plan for `reviews/CROSSLIB-BENCH-REQUIREMENTS.md`: port the kernel2 runtime board to ZIO,
cats-effect, zio-blocks Async and Turbolift, and join the result with the existing kernel2 and old-kernel
gate numbers.

Nothing here has been built or run. Every API claim below is quoted from the artifact's own published
sources (sources jars pulled from Maven Central), not from memory; the quote and its file:line are given
inline so the implementer can re-check any of them.

---

## 0. Verified references

Versions read from Maven Central `maven-metadata.xml` on 2026-08-21.

| library | artifact | version | source of truth |
|---|---|---|---|
| ZIO | `dev.zio %% zio` | **2.1.26** | `https://repo1.maven.org/maven2/dev/zio/zio_3/maven-metadata.xml` (`<latest>` and `<release>` both 2.1.26) |
| cats-effect | `org.typelevel %% cats-effect` | **3.7.0** | `https://repo1.maven.org/maven2/org/typelevel/cats-effect_3/maven-metadata.xml` |
| zio-blocks Async | `dev.zio %% zio-blocks-async` | **0.0.51** | `https://repo1.maven.org/maven2/dev/zio/zio-blocks-async_3/maven-metadata.xml` (`<latest>` 0.0.51, lastUpdated 20260731) |
| Turbolift | `io.github.marcinzh %% turbolift-core` | **0.126.0** (requirements said 0.114.0, stale) | `https://repo1.maven.org/maven2/io/github/marcinzh/turbolift-core_3/maven-metadata.xml` (`<latest>` 0.126.0, lastUpdated 20260309) |

Corroborating sources:

- ZIO releases: `https://github.com/zio/zio/releases`
- cats-effect on Central: `https://central.sonatype.com/artifact/org.typelevel/cats-effect`
- zio-blocks repo and README: `https://github.com/zio/zio-blocks`, `https://raw.githubusercontent.com/zio/zio-blocks/main/README.md`
- Turbolift repo and README: `https://github.com/marcinzh/turbolift`, `https://raw.githubusercontent.com/marcinzh/turbolift/master/README.md`
- Turbolift microsite: `https://marcinzh.github.io/turbolift/`
- Turbolift scaladoc: `https://javadoc.io/doc/io.github.marcinzh/turbolift-core_3`
- Turbolift's own cross-library microbenchmark suite (prior art worth a sanity cross-check): `https://github.com/marcinzh/effect-zoo`

### 0.1 Scala 3.8.4 compatibility

All four artifacts publish native `_3` builds. Neither `CrossVersion.for3Use2_13` nor any 2.13 shim is
needed anywhere.

Both new dependencies were compiled against the Scala 3 LTS library and declare it transitively:

- `turbolift-core_3-0.126.0.pom` declares `org.scala-lang:scala3-library_3:3.3.7`
- `zio-blocks-async_3-0.0.51.pom` declares `org.scala-lang:scala3-library_3:3.3.7`, plus
  `dev.zio:zio-blocks-combinators_3:0.0.51` and `io.github.dotty-cps-async:dotty-cps-async_3:1.3.4`

TASTy is backward compatible within the Scala 3 series, so a 3.8.4 compiler reads 3.3.7 TASTy. sbt will
evict `scala3-library_3:3.3.7` in favour of the build's 3.8.4; that is the ordinary and correct outcome
(risk R5 below covers the eviction warning).

ZIO 2.1.26 and cats-effect 3.7.0 are already resolved by this build (`build.sbt:31` `val zioVersion =
"2.1.26"`, `build.sbt:32` `val catsVersion = "3.7.0"`, used by `kyo-zio` and `kyo-cats`), so they add no
new resolution surface at all.

### 0.2 zio-blocks Async: the claimed API exists exactly as assumed

The requirements assumed `Async.succeed` / `map` / `flatMap` / `.block` and "a completed `Async[A]` IS the
`A`". All four claims are literally true.

The representation, from `zio/blocks/async/AsyncEncoding.scala`:

```scala
  val Instance: AsyncEncoding = new AsyncEncoding {
    type Async[+A] = Any
  }
```

and the lift, same file:

```scala
  def liftSuccess[A](a: A): Async[A] = {
    val any = a.asInstanceOf[Any]
    any match {
      case w: WrappedPollable               => nest(w).asInstanceOf[Async[A]]
      case p if p.isInstanceOf[Pollable[_]] =>
        wrap(p.asInstanceOf[Pollable[?]]).asInstanceOf[Async[A]]
      case _ => a.asInstanceOf[Async[A]]
    }
  }
```

`Async.succeed(a) = AsyncEncoding.liftSuccess(a)` (`zio/blocks/async/Async.scala`), so for a non-`Pollable`
`A` the `Async[A]` *is* the boxed `A`.

The operators, from `zio/blocks/async/AsyncSyntaxVersionSpecific.scala:36-88`:

```scala
  extension [A](inline fa: Async[A]) {
    inline def flatMap[B](inline f: A => Async[B]): Async[B] = {
      val r: Any = fa
      if (r.isInstanceOf[Failure]) r.asInstanceOf[Async[B]]
      else if (r.isInstanceOf[Pollable[?]])
        Async.slowPath.flatMapAsync[A, B](r, (a: A) => f(a))
      else f(AsyncEncoding.deliverSuccess[A](r))
    }

    inline def map[B](inline f: A => B): Async[B] = { ... }

    inline def block: A = Async.slowPath.block[A](fa)
  }
```

and `block`'s ready path, `zio/blocks/async/Async.scala:408-422`, ends in `else fa.asInstanceOf[A]`: no
thread, no parker, no runtime.

The README states the same, and gives the coordinate:

```scala
libraryDependencies += "dev.zio" %% "zio-blocks-async" % "0.0.51"

val computed: Int =
  Async.succeed(20).map(_ + 1).flatMap(n => Async.succeed(n * 2)).block
```

**Consequence the requirements did not anticipate, and the single most important fact about the zio-blocks
column:** `flatMap` on a *ready* value calls the continuation directly (`else f(...)` above). There is no
trampoline on the ready path. Every zio-blocks row that recurses through `flatMap` over settled values
recurses on the JVM call stack, one frame per level. At `Depth = 10000` that needs stack headroom (see
section 1, `Jmh / javaOptions`, and risk R1). It also means zio-blocks is not measuring "an evaluator" on
those rows at all: it is measuring direct Scala recursion with a type test per step. That is a legitimate
and interesting result, but the table must say so rather than let a reader infer a like-for-like
comparison.

### 0.3 Turbolift: Reader / State / handler / run verified

Effect definition and run entry, from the project README (canonical spelling):

```scala
case object MyReader extends ReaderEffect[Int]
case object MyState extends StateEffect[Int]

val result =
  program
  .handleWith(MyState.handler(100))
  .handleWith(MyReader.handler(3))
  .run
```

From `turbolift/effects/Reader.scala`:

```scala
trait ReaderEffect[R] extends Effect[ReaderSignature[R]] with ReaderSignature[R]:
  final override val ask: R !! this.type = perform(_.ask)
  final override def asks[A](f: R => A): A !! this.type = perform(_.asks(f))
  final override def asksEff[A, U <: this.type](f: R => A !! U): A !! U = perform(_.asksEff(f))
```

and its handler factory, same file:

```scala
object ReaderEffect:
  extension [R](thiz: ReaderEffect[R])
    def handler(initial: R): Handler[Identity, Identity, thiz.type, Any] = thiz.handlers.default(initial)
```

with the default handler implemented as a `Stateful` interpreter over `Local`:

```scala
    def default(initial: R): Handler[Identity, Identity, enclosing.type, Any] =
      new impl.Stateful[Identity, Identity, Any] with impl.Parallel.Trivial with ReaderSignature[R]:
        override type Local = R
        override def onInitial: R !! Any = !!.pure(initial)
        override val ask: R !! ThisEffect = Local.get
        override def asksEff[A, U <: ThisEffect](f: R => A !! U): A !! U = Local.getsEff(f)
        ...
      .toHandler
```

From `turbolift/effects/State.scala`, the one-operation read-and-advance used by the stateful row:

```scala
trait StateSignature[S] extends Signature:
  def update[A](f: S => (A, S)): A !! ThisEffect
```

```scala
object StateEffect:
  extension (thiz: PolyStateEffect)
    def handler[S](initial: S): Handler[Identity, (_, S), thiz.@@[S, S], Any] = thiz.handlers.local(initial)
```
(the monomorphic `StateEffect[S]` exposes `handlers.local(initial): Handler[Identity, (_, S), enclosing.type, Any]`.)

Run entries, `turbolift/Computation.scala:367-372`:

```scala
  extension [A](thiz: Computation[A, Any])
    def run(using mode: Mode = Mode.default): A = Executor.pick(mode).runSync(thiz, "").get
    def runST: A = Executor.ST.runSync(thiz, "").get
    def runMT: A = Executor.MT.runSync(thiz, "").get
```

and `turbolift/mode/Mode.scala`:

```scala
object Mode:
  val default: Mode = MT
```

`Identity` comes from `turbolift/Extensions.scala:18` (`type Identity[X] = X`), reachable via
`import turbolift.Extensions.*`.

**Stack safety at depth 10000 is not a concern and needs no setting.** Turbolift's engine is an explicit
CEK-style machine with a heap-allocated `Stack` / `Store`, driven by `@tailrec` loops:
`turbolift/internals/engine/Engine.scala:45` `@tailrec private[engine] final def outerLoop()`,
`:75` `@tailrec private final def middleLoop(): Halt`,
`:132` `@tailrec private final def innerLoop(tag: Tag, payload: Any, step: Step, stack: Stack, store: Store, hasShadow: Boolean): Halt`.
Continuations live in `turbolift/internals/engine/stacked/`, not on the JVM stack. Its own preemption
budget is `tickLow = 1000`, `tickHigh = 20` (`turbolift/internals/engine/Env.scala:65-66`), which is the
structural analogue of kyo's `Period`. There is nothing to adjust.

### 0.4 ZIO: entry, ambient, state

`zio/Runtime.scala:258` `val default: Runtime[Any] = Runtime(ZEnvironment.empty, FiberRefs.empty, RuntimeFlags.default)`.

`zio/RuntimeFlags.scala:203-208`:

```scala
  val default: RuntimeFlags =
    RuntimeFlags(
      RuntimeFlag.FiberRoots,
      RuntimeFlag.Interruption,
      RuntimeFlag.CooperativeYielding
    )
```

`zio/Runtime.scala:133` `def run[E, A](zio: ZIO[R, E, A])(implicit trace: Trace, unsafe: Unsafe): Exit[E, A]`,
delegating to `runOrFork`, which at `:152-159` allocates a `FiberRuntime` and a per-run `FiberRefs`
update; a fully synchronous effect returns `Right(exit)` and never leaves the calling thread.

`zio/Unsafe.scala:36` `def unsafe[A](f: Unsafe => A): A`.
`zio/ZIO.scala:6600` `final def getOrThrowFiberFailure()(implicit unsafe: Unsafe): A`.

Constructors and combinators:
`zio/ZIOCompanionVersionSpecific.scala:174`

```scala
  inline def succeed[A](inline a: Unsafe ?=> A)(implicit inline trace: Trace): ZIO[Any, Nothing, A] =
    ZIO.Sync(trace, () => a(using Unsafe))
```

`zio/ZIO.scala:981` `def map[B](f: A => B)(implicit trace: Trace): ZIO[R, E, B] = ZIO.Mapped(trace, self, f)`
`zio/ZIO.scala:645` `def flatMap[R1 <: R, E1 >: E, B](k: A => ZIO[R1, E1, B])(implicit trace: Trace): ZIO[R1, E1, B] = ZIO.FlatMap(trace, self, k)`
`zio/ZIO.scala:6453` `sealed trait Exit[+E, +A] extends ZIO[Any, E, A]`

FiberRef, `zio/FiberRef.scala`:

```scala
:116  def get(implicit trace: Trace): UIO[A] = modify { v => (v, v) }
:154  def getWith[R, E, B](f: A => ZIO[R, E, B])(implicit trace: Trace): ZIO[R, E, B] = get.flatMap(f)
:162  def locally[R, E, B](newValue: A)(zio: ZIO[R, E, B])(implicit trace: Trace): ZIO[R, E, B] =
        ZIO.acquireReleaseWith(getAndSet(newValue))(set)(_ => zio)
:195  def modify[B](f: A => (B, A))(implicit trace: Trace): UIO[B] =
        ZIO.withFiberRuntime[Any, Nothing, B] { (fiberState, _) => ... }
:420  def make[A](initial: A, fork: A => A = ..., join: (A, A) => A = ...)(implicit unsafe: Unsafe): FiberRef.WithPatch[A, A => A]
```

Environment, `zio/ZIO.scala`:

```scala
:4777 def service[A: Tag](implicit trace: Trace): URIO[A, A] = serviceWith(identity)
:1276 final def provideEnvironment(r: => ZEnvironment[R])(implicit trace: Trace): IO[E, A] =
        FiberRef.currentEnvironment.locallyWith(_ => r)(self.asInstanceOf[ZIO[Any, E, A]])
```

### 0.5 cats-effect: entry, IOLocal

`cats/effect/IOPlatform.scala:41`:

```scala
  final def unsafeRunSync()(implicit runtime: unsafe.IORuntime): A =
    unsafeRunTimed(Long.MaxValue.nanos).get
```

and `:70-82`, which is what makes the CE entry expensive:

```scala
  final def unsafeRunTimed(limit: FiniteDuration)(implicit runtime: unsafe.IORuntime): Option[A] = {
    val queue = new ArrayBlockingQueue[Either[Throwable, A]](1)
    val fiber = unsafeRunAsyncImpl { r => queue.offer(r); () }
    ...
      val result = blocking(queue.poll(limit.toNanos, TimeUnit.NANOSECONDS))
```

Every `unsafeRunSync()` allocates an `ArrayBlockingQueue(1)`, schedules a fiber onto the work-stealing
pool, and parks the calling thread until the pool hands the result back. That is a thread handoff per
operation, and it will dominate every fast row. See section 4.2 and risk R2.

`cats/effect/unsafe/implicits.scala`: `implicit def global: IORuntime = IORuntime.global`.

`cats/effect/IO.scala`: `:1640 def pure[A](value: A): IO[A] = Pure(value)`,
`:579 def map[B](f: A => B): IO[B] = IO.Map(this, f, Tracing.calculateTracingEvent(f))`,
`:469 def flatMap[B](f: A => IO[B]): IO[B] = IO.FlatMap(this, f, Tracing.calculateTracingEvent(f))`.

`cats/effect/IOLocal.scala`:

```scala
:152  final def get: IO[A] = IO.Local(state => (state, getOrDefault(state)))
:158  final def set(value: A): IO[Unit] = IO.Local(state => (set(state, value), ()))
:179  final def modify[B](f: A => (A, B)): IO[B] = IO.Local { state => val (a2, b) = f(getOrDefault(state)); (set(state, a2), b) }
:285  def apply[A](default: A): IO[IOLocal[A]] = IO(new IOLocalImpl(default))
:305  def getOrDefault(state: IOLocalState): A = state.getOrElse(this, default).asInstanceOf[A]
```

A fresh fiber starts with an empty `IOLocalState`, so `local.get` on a fresh run returns the constructor
default. That is the CE analogue of an installed handler.

Tracing, `cats/effect/tracing/TracingConstants.java`:

```java
  private static final String stackTracingMode =
      Optional.ofNullable(System.getProperty("cats.effect.tracing.mode"))
          .filter(x -> !x.isEmpty())
          .orElse("cached");
```

so by default every `map` / `flatMap` **construction** does `key.getClass` plus a striped-hashtable lookup
(`cats/effect/tracing/TracingPlatform.scala:31-40`). Default is measured; `-Dcats.effect.tracing.mode=none`
is the recorded alternative (section 5.4).

---

## 1. sbt changes

Two new version vals next to the existing ones at `build.sbt:31-34`:

```scala
val zioBlocksVersion = "0.0.51"
val turboliftVersion = "0.126.0"
```

One new project, placed immediately after `kyo-kernel2` in `build.sbt` (the `kyo-kernel-bench` block at
`build.sbt:734` is the precedent this follows line for line):

```scala
// Cross-library board: the kernel2 KernelBench row shapes ported to ZIO, cats-effect, zio-blocks Async
// and Turbolift, for the comparison tables. A separate project so the external effect libraries live on
// exactly one classpath and can never reach a published kyo pom; it is unpublished and nothing depends
// on it.
lazy val `kyo-kernel2-bench-cross` =
    project
        .in(file("kyo-kernel2/bench-cross"))
        .enablePlugins(JmhPlugin)
        .dependsOn(`kyo-kernel2`.jvm)
        .disablePlugins(MimaPlugin)
        .settings(
            `kyo-settings`,
            publish / skip := true,
            // No tests or doctests here; keep the doctest driver jars (built from the stack above the
            // kernel, mid-migration) off the Test classpath that Jmh extends.
            Test / unmanagedJars := Seq.empty,
            // Same JVM flags the kernel2 and old-kernel boards ran under, so gc.alloc.rate.norm is
            // comparable across every column: a collector-dependent object layout flag must not be
            // baked into one board's bytes and not another's.
            Jmh / javaOptions := (Test / javaOptions).value.filterNot(_ == "-XX:+UseCompactObjectHeaders"),
            // zio-blocks Async has no trampoline: flatMap over a settled value calls the continuation
            // directly, so the depth-10000 rows recurse on the JVM stack. The flag is applied uniformly
            // to every class in this project, and the kernel rows are stack-insensitive (see 5.5).
            Jmh / javaOptions += "-Xss32m",
            // Turbolift's map/flatMap are inline methods whose bodies instantiate an anonymous class;
            // the resulting "anonymous class definition will be duplicated at each inline site" note
            // would otherwise be fatal under kyo-settings' -Werror.
            scalacOptions += "-Wconf:msg=anonymous class definition:silent",
            libraryDependencies += "dev.zio"          %% "zio"              % zioVersion,
            libraryDependencies += "org.typelevel"    %% "cats-effect"      % catsVersion,
            libraryDependencies += "dev.zio"          %% "zio-blocks-async" % zioBlocksVersion,
            libraryDependencies += "io.github.marcinzh" %% "turbolift-core" % turboliftVersion
        )
```

Notes on each line that differs from `kyo-kernel-bench`:

- `-Xss32m` is new. `kyo-compile-bench` already sets `Jmh / javaOptions ++= Seq("-Xss32m", "-Xmx4g")`
  (`build.sbt:767`), so the flag has precedent in this build. It is required by zio-blocks (section 0.2)
  and is applied to all four classes so no library gets a different JVM.
- The `-Wconf` line is new and is a compile-reality accommodation, not a quality relaxation:
  `turbolift/Computation.scala:39-47` carries `@nowarn("msg=anonymous class definition")` on its own
  `inline def map` / `inline def flatMap` for exactly this reason. If the build turns out not to emit the
  note at our call sites, delete the line rather than leave a dead suppression.
- `disablePlugins(MimaPlugin)` and `Test / unmanagedJars := Seq.empty` are copied verbatim from
  `kyo-kernel-bench`; both are load-bearing mid-migration.

**The hard constraint is satisfied structurally:** nothing in the build depends on
`kyo-kernel2-bench-cross`, and `publish / skip := true` keeps it out of the publish graph, so no external
coordinate can appear in any kyo pom.

---

## 2. File layout

Sources live in the Compile configuration, the same as `kyo-kernel-bench` (`kyo-kernel/bench/src/main/scala`):

```
kyo-kernel2/bench-cross/src/main/scala/kyo/kernel/bench/cross/
    ZioBench.scala
    CatsEffectBench.scala
    ZioBlocksBench.scala
    TurboliftBench.scala
```

Package `kyo.kernel.bench.cross`. One class per library, no shared base class and no shared mutable state
(fairness rule 4). Each class carries the same JMH annotation block as `KernelBench`:

```scala
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2)
```

Each class repeats the constants in its own companion (`Depth = 10000`, `NarrowDepth = 1000`,
`FusedDepth = 32`) rather than importing them, so a class is readable and movable on its own; the values
are pinned by the fairness rule and cross-checked by the results script (section 5.6).

**Row names are byte-identical to `KernelBench`'s**, so the results tables join on the benchmark's last
path segment.

Each file opens with a scaladoc block that states, per row group, the exact substitution being made and
its fidelity, so no table can be read as claiming an exact match where there is none (fairness rule 3).
Section 3's per-row notes are the source text for those scaladocs.

### 2.1 The one translation rule that governs every body

kyo's `map` is bind: `(x: Int < Any).map(f)` unnests when `f` returns a pending value. In every other
library `map` is a functor map and cannot unnest. So, mechanically:

- a kyo `.map(f)` whose `f` returns a **plain value** becomes `.map(f)`
- a kyo `.map(f)` whose `f` returns a **pending value** becomes `.flatMap(f)`

Applied to the board: the ten `.map(v => (v + 1) & 63)` links stay `map` everywhere; the trailing
`.map(_ => loop(i + 1))`, `.map(loop)`, `.map(a => loop(i + a))` and the `ask.map { a => ... }` bodies all
become `flatMap`. This is stated once here and assumed in every sketch below.

---

## 3. Per-row, per-library bodies

Elision convention: a ten-link chain is written out in full (four lines) exactly as `KernelBench` writes
it. The fifty-link `accumulatedChain` is written as its first link plus `// x49 more, verbatim from
KernelBench.accumulatedChain`; the implementer copies the block.

### 3.0 Row inventory and tier assignment

`KernelBench` has 20 rows. The requirements' tier tables do not cover all of them; the plan assigns the
remainder and says so.

| # | row | tier | note |
|---|---|---|---|
| 1 | `evalFixedOverhead` | **skip** | Not in the requirements' tables. Its own scaladoc says the single-shot row "sits at ~10ns, inside the harness's own resolution, so its cross-kernel ratio carries no signal"; that argument holds a fortiori across libraries. `evalFixedOverheadBatch` supersedes it. |
| 2 | `evalFixedOverheadBatch` | A | |
| 3 | `fusionAllocatesNothing` | A | |
| 4 | `fusionPastBudgetPaysRescuesOnly` | A | |
| 5 | `uncachedValuesPayBoxingOnly` | A | |
| 6 | `userTypesSkipKernelWrapping` | A | |
| 7 | `inlineLimitCostsTimeNotAllocation` | **C skip** | Pins kyo inline mechanics (C2 caller inline budget against kyo's inline `map` sites), not evaluator work. |
| 8 | `inlineLimitKeepsZeroAllocation` | **C skip** | Same reason. |
| 9 | `continuationBodiesFuse` | B | |
| 10 | `fusionAfterSuspensionRunOnly` | B | degenerate for zio-blocks, see 3.11 |
| 11 | `fusionAfterSuspension` | B | |
| 12 | `deepRecursionPaysRescuesOnly` | A | the requirements name `deepRecursionNoRescue` / `OneRescue` too; those rows exist only on `ProtoKernelBench`, not on `KernelBench`, so only this one is ported |
| 13 | `suspensionBaseline` | B | |
| 14 | `suspensionFusesContinuation` | B | duplicate of 13 for ZIO/CE/zio-blocks, see 3.13 |
| 15 | `sharedHandlerPaysDispatch` | B | |
| 16 | `idleHandlerAddsNothing` | B | **unclassified in the requirements**; plan classifies it B, skipped for zio-blocks |
| 17 | `handleLoopAnswersInPlace` | **C skip, deviation** | see 3.16 |
| 18 | `statefulAnswersPaySuccessor` | B | |
| 19 | `trailingMapsStayLinear` | A shape / B ambient | see 3.18 |
| 20 | `foreignCrossingsPayRotation` | B | skipped for zio-blocks per the requirements |

`emittingClausesPayRegionRebuild` and the other Proto-only rows named in the requirements' Tier C live on
`ProtoKernelBench`, not on `KernelBench`; they are out of scope for the same reason the requirements give
(region rebuild has no portable analogue).

### 3.1 Shared preamble per library

**ZioBench.scala**

```scala
package kyo.kernel.bench.cross

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import zio.*

object ZioBench:

    inline def Depth       = 10000
    inline def NarrowDepth = 1000
    inline def FusedDepth  = 32

    final case class Box(value: Int)

    /** One runtime for the whole class, built once at class-init. Constructing a Runtime per
      * operation would measure runtime construction, not evaluation.
      */
    val runtime: Runtime[Any] = Runtime.default

    /** The ambient answer. A FiberRef's initial value is what a fresh fiber reads, so constructing
      * it with 1 is the install; that is the ZIO analogue of kyo's handler being a per-run constant.
      */
    val ask: FiberRef[Int]  = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(1))
    val ask2: FiberRef[Int] = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(0))
    val st: FiberRef[Int]   = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(0))

    def runSync[A](z: ZIO[Any, Nothing, A]): A =
        Unsafe.unsafe(implicit u => runtime.unsafe.run(z).getOrThrowFiberFailure())

end ZioBench
```

**CatsEffectBench.scala**

```scala
package kyo.kernel.bench.cross

import cats.effect.IO
import cats.effect.IOLocal
import cats.effect.unsafe.implicits.global
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*

object CatsEffectBench:

    inline def Depth       = 10000
    inline def NarrowDepth = 1000
    inline def FusedDepth  = 32

    final case class Box(value: Int)

    /** IOLocal's constructor default is what a fresh fiber reads, so 1 is the installed answer. */
    val ask: IOLocal[Int]  = IOLocal(1).unsafeRunSync()
    val ask2: IOLocal[Int] = IOLocal(0).unsafeRunSync()
    val st: IOLocal[Int]   = IOLocal(0).unsafeRunSync()

    def runSync[A](io: IO[A]): A = io.unsafeRunSync()

end CatsEffectBench
```

**ZioBlocksBench.scala**

```scala
package kyo.kernel.bench.cross

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import zio.blocks.async.*

object ZioBlocksBench:

    inline def Depth       = 10000
    inline def NarrowDepth = 1000
    inline def FusedDepth  = 32

    final case class Box(value: Int)

    /** zio-blocks has no effect system, so there is no ambient value and no handler. The Tier B rows
      * substitute a pre-completed `Async.succeed(1)` read; fidelity LOW, recorded per row.
      */
    val answer: Async[Int] = Async.succeed(1)

end ZioBlocksBench
```

**TurboliftBench.scala**

```scala
package kyo.kernel.bench.cross

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import turbolift.!!
import turbolift.Extensions.*
import turbolift.Handler
import turbolift.effects.ReaderEffect
import turbolift.effects.StateEffect

object TurboliftBench:

    inline def Depth       = 10000
    inline def NarrowDepth = 1000
    inline def FusedDepth  = 32

    final case class Box(value: Int)

    case object Ask extends ReaderEffect[Int]
    type Ask = Ask.type

    case object Ask2 extends ReaderEffect[Int]
    type Ask2 = Ask2.type

    case object St extends StateEffect[Int]
    type St = St.type

    /** Handlers built once; only their application is per run, which is exactly kyo's per-run
      * `ArrowEffect.handleCont(...)` application.
      */
    val askHandler: Handler[Identity, Identity, Ask, Any]   = Ask.handler(1)
    val ask2Handler: Handler[Identity, Identity, Ask2, Any] = Ask2.handler(0)
    val stHandler: Handler[Identity, (_, Int), St, Any]     = St.handler(0)

end TurboliftBench
```

Turbolift run entry: **`runST`, not `run`.** `Mode.default` is `MT`
(`turbolift/mode/Mode.scala`), which ships the fiber to a thread pool and parks the caller; for a
synchronous microbenchmark that measures a thread handoff. `runST` uses `ZeroThreadedExecutor`, which
drives the fiber on the calling thread (`turbolift/internals/executor/ZeroThreadedExecutor.scala:191-198`).
The choice, and the fact that `Executor.ST` is `def zero() = new ZeroThreadedExecutor` and therefore
allocates one executor per run, is documented in the class scaladoc.

### 3.2 evalFixedOverheadBatch (Tier A)

kernel2 reference:

```scala
    @Benchmark
    @OperationsPerInvocation(1000)
    def evalFixedOverheadBatch: Int =
        var acc = 0
        var i   = 0
        while i < 1000 do
            acc += (((seed + i): Int < Any).map(_ + 1)).eval
            i += 1
        acc
```

Each class carries `private var seed = 1` exactly as `KernelBench` does, and the same accumulate-and-vary
trick, so no iteration can be hoisted or folded.

ZIO:

```scala
    @Benchmark
    @OperationsPerInvocation(1000)
    def evalFixedOverheadBatch: Int =
        var acc = 0
        var i   = 0
        while i < 1000 do
            acc += runSync(ZIO.succeed(seed + i).map(_ + 1))
            i += 1
        acc
```

cats-effect:

```scala
    @Benchmark
    @OperationsPerInvocation(1000)
    def evalFixedOverheadBatch: Int =
        var acc = 0
        var i   = 0
        while i < 1000 do
            acc += runSync(IO.pure(seed + i).map(_ + 1))
            i += 1
        acc
```

zio-blocks:

```scala
    @Benchmark
    @OperationsPerInvocation(1000)
    def evalFixedOverheadBatch: Int =
        var acc = 0
        var i   = 0
        while i < 1000 do
            acc += Async.succeed(seed + i).map(_ + 1).block
            i += 1
        acc
```

Turbolift:

```scala
    @Benchmark
    @OperationsPerInvocation(1000)
    def evalFixedOverheadBatch: Int =
        var acc = 0
        var i   = 0
        while i < 1000 do
            acc += (!!.pure(seed + i).map(_ + 1)).runST
            i += 1
        acc
```

### 3.3 fusionAllocatesNothing (Tier A, FusedDepth = 32)

ZIO:

```scala
    @Benchmark
    def fusionAllocatesNothing: Int =
        def loop(i: Int): UIO[Int] =
            if i > FusedDepth then ZIO.succeed(0)
            else
                ZIO.succeed(i & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(_ => loop(i + 1))
        runSync(loop(0))
    end fusionAllocatesNothing
```

cats-effect: identical with `IO.pure(i & 63)` and `IO[Int]`.
zio-blocks: identical with `Async.succeed(i & 63)`, `Async[Int]`, and `.block` in place of `runSync`.
Turbolift: identical with `!!.pure(i & 63)`, `Int !! Any`, and `.runST`.

### 3.4 fusionPastBudgetPaysRescuesOnly (Tier A, NarrowDepth = 1000)

Byte-identical to 3.3 with `NarrowDepth` in place of `FusedDepth`. For kyo this row is the trampoline
row; for the ported libraries it measures the same thing their own machinery does at 12012 steps.

### 3.5 uncachedValuesPayBoxingOnly (Tier A, NarrowDepth)

ZIO:

```scala
    @Benchmark
    def uncachedValuesPayBoxingOnly: Int =
        def loop(i: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(i)
            else
                ZIO.succeed(i + 11)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .flatMap(loop)
        runSync(loop(0))
    end uncachedValuesPayBoxingOnly
```

cats-effect / zio-blocks / Turbolift: same shape with each library's `pure` and run entry.

### 3.6 userTypesSkipKernelWrapping (Tier A, NarrowDepth)

ZIO:

```scala
    @Benchmark
    def userTypesSkipKernelWrapping: Int =
        def loop(b: Box): UIO[Box] =
            if b.value > NarrowDepth then ZIO.succeed(b)
            else
                ZIO.succeed(Box(b.value + 11))
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1))
                    .flatMap(loop)
        runSync(loop(Box(0))).value
    end userTypesSkipKernelWrapping
```

cats-effect / Turbolift: same shape.
zio-blocks: same shape, and worth a per-row note: `Box` is not a `Pollable`, so `Async.succeed(Box(...))`
stores the `Box` itself with no wrapper, which is the property this row is testing on the kyo side too.

### 3.7 deepRecursionPaysRescuesOnly (Tier A, Depth = 10000)

ZIO:

```scala
    @Benchmark
    def deepRecursionPaysRescuesOnly: Int =
        def loop(i: Int): UIO[Int] =
            ZIO.unit.flatMap { _ =>
                if i > Depth then ZIO.succeed(0) else loop(i + 1)
            }
        runSync(loop(0))
    end deepRecursionPaysRescuesOnly
```

cats-effect: `IO.unit.flatMap { ... }`.
Turbolift: `!!.unit.flatMap { ... }`.
zio-blocks:

```scala
    @Benchmark
    def deepRecursionPaysRescuesOnly: Int =
        def loop(i: Int): Async[Int] =
            Async.succeed(()).flatMap { _ =>
                if i > Depth then Async.succeed(0) else loop(i + 1)
            }
        loop(0).block
    end deepRecursionPaysRescuesOnly
```

**zio-blocks row note (mandatory in the file's scaladoc):** the value is ready at every step, so `flatMap`
calls the continuation directly and this is 10000 frames of ordinary JVM recursion, not a trampoline. It
is the row where the "no runtime" design shows its cost model most sharply, and it is why the project sets
`-Xss32m`.

### 3.8 suspensionBaseline (Tier B, Depth = 10000)

kernel2 reference: `ask.map(a => loop(i + a))` under `handleCont(...)([X] => (_, cont) => cont(1), a => a)`.

ZIO (substitution: per-suspension ambient read from a `FiberRef`; see section 4.1 for why not
`ZIO.service`):

```scala
    @Benchmark
    def suspensionBaseline: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i)
            else ask.get.flatMap(a => loop(i + a))
        runSync(loop(0))
    end suspensionBaseline
```

cats-effect (substitution: `IOLocal.get`):

```scala
    @Benchmark
    def suspensionBaseline: Int =
        def loop(i: Int): IO[Int] =
            if i > Depth then IO.pure(i)
            else ask.get.flatMap(a => loop(i + a))
        runSync(loop(0))
    end suspensionBaseline
```

Turbolift (exact: a Reader operation resolved by its installed handler):

```scala
    @Benchmark
    def suspensionBaseline: Int =
        def loop(i: Int): Int !! Ask =
            if i > Depth then !!.pure(i)
            else Ask.ask.flatMap(a => loop(i + a))
        loop(0).handleWith(askHandler).runST
    end suspensionBaseline
```

zio-blocks (substitution: a pre-completed `Async` read; fidelity **LOW**, there is no suspension and no
handler):

```scala
    @Benchmark
    def suspensionBaseline: Int =
        def loop(i: Int): Async[Int] =
            if i > Depth then Async.succeed(i)
            else answer.flatMap(a => loop(i + a))
        loop(0).block
    end suspensionBaseline
```

### 3.9 suspensionFusesContinuation (Tier B, Depth)

kernel2 uses `askWith(a => loop(i + a))`, the suspend-carrying-its-continuation form.

Turbolift has the genuine analogue, `asksEff`, which the interpreter answers with `Local.getsEff(f)`:

```scala
    @Benchmark
    def suspensionFusesContinuation: Int =
        def loop(i: Int): Int !! Ask =
            if i > Depth then !!.pure(i)
            else Ask.asksEff(a => loop(i + a))
        loop(0).handleWith(askHandler).runST
    end suspensionFusesContinuation
```

ZIO spells it `ask.getWith(a => loop(i + a))`, and cats-effect and zio-blocks have no distinct spelling at
all (`local.get.flatMap(f)` / `answer.flatMap(f)`). **Row note for those three:** ZIO's
`FiberRef.getWith` is defined as `get.flatMap(f)` (`zio/FiberRef.scala:154`), so for ZIO, CE and
zio-blocks this row is expected to equal `suspensionBaseline`; the measurement is kept because confirming
the equality is itself the finding, and because a divergence would mean something surprising. Turbolift is
the only column where the row can differ by construction.

### 3.10 continuationBodiesFuse (Tier B, NarrowDepth)

ZIO:

```scala
    @Benchmark
    def continuationBodiesFuse: Int =
        def loop(i: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(i)
            else
                ask.get.flatMap { a =>
                    ZIO.succeed((i + a) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63)
                        .flatMap(_ => loop(i + 1))
                }
        runSync(loop(0))
    end continuationBodiesFuse
```

cats-effect / zio-blocks: same shape with their `pure` / read / entry.
Turbolift: same shape with `Ask.ask.flatMap { a => !!.pure((i + a) & 63)... }`, handled and `runST`.

### 3.11 fusionAfterSuspensionRunOnly (Tier B)

The chain is built once as a field and only answered per operation.

ZIO field:

```scala
    private val accumulatedChain: UIO[Int] =
        ZioBench.ask.get.map(a => a & 63)
            .map(v => (v + 1) & 63)  // x49 more, verbatim from KernelBench.accumulatedChain
```

ZIO row: `@Benchmark def fusionAfterSuspensionRunOnly: Int = runSync(accumulatedChain)`

cats-effect: field `private val accumulatedChain: IO[Int] = CatsEffectBench.ask.get.map(a => a & 63).map(...)x49`,
row `runSync(accumulatedChain)`.

Turbolift: field `private val accumulatedChain: Int !! Ask = Ask.ask.map(a => a & 63).map(...)x49`,
row `accumulatedChain.handleWith(askHandler).runST`.

zio-blocks: **degenerate, and the table must say so.** `Async.succeed(1).map(...)` over a ready value
evaluates at field initialisation (`map`'s ready branch is `Async.succeed(f(...))`), so the field holds a
settled `Int` and the row measures `.block` on an already-computed value. Fidelity **NONE**. It is
reported with that label rather than omitted, because "the chain folded at construction" is the honest
answer to what this row asks of zio-blocks.

### 3.12 fusionAfterSuspension (Tier B, NarrowDepth)

ZIO:

```scala
    @Benchmark
    def fusionAfterSuspension: Int =
        def loop(i: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(i)
            else
                ask.get
                    .map(a => (i + a) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .flatMap(_ => loop(i + 1))
        runSync(loop(0))
    end fusionAfterSuspension
```

cats-effect / zio-blocks / Turbolift: same shape with their read and entry.

### 3.13 sharedHandlerPaysDispatch (Tier B, Depth)

Sixteen distinct call sites resolving through one ambient, so the receiver profile overflows.

ZIO:

```scala
    @Benchmark
    def sharedHandlerPaysDispatch: Int =
        def s0(i: Int): UIO[Int]  = if i > Depth then ZIO.succeed(i) else ask.getWith(a => s1(i + a))
        def s1(i: Int): UIO[Int]  = ask.getWith(a => s2(i + a))
        def s2(i: Int): UIO[Int]  = ask.getWith(a => s3(i + a))
        def s3(i: Int): UIO[Int]  = ask.getWith(a => s4(i + a))
        def s4(i: Int): UIO[Int]  = ask.getWith(a => s5(i + a))
        def s5(i: Int): UIO[Int]  = ask.getWith(a => s6(i + a))
        def s6(i: Int): UIO[Int]  = ask.getWith(a => s7(i + a))
        def s7(i: Int): UIO[Int]  = ask.getWith(a => s8(i + a))
        def s8(i: Int): UIO[Int]  = ask.getWith(a => s9(i + a))
        def s9(i: Int): UIO[Int]  = ask.getWith(a => s10(i + a))
        def s10(i: Int): UIO[Int] = ask.getWith(a => s11(i + a))
        def s11(i: Int): UIO[Int] = ask.getWith(a => s12(i + a))
        def s12(i: Int): UIO[Int] = ask.getWith(a => s13(i + a))
        def s13(i: Int): UIO[Int] = ask.getWith(a => s14(i + a))
        def s14(i: Int): UIO[Int] = ask.getWith(a => s15(i + a))
        def s15(i: Int): UIO[Int] = ask.getWith(a => s0(i + a))
        runSync(s0(0))
    end sharedHandlerPaysDispatch
```

cats-effect: `ask.get.flatMap(a => sN(i + a))` at each site.
zio-blocks: `answer.flatMap(a => sN(i + a))` at each site; fidelity LOW as elsewhere.
Turbolift: `Ask.asksEff(a => sN(i + a))` at each site, then `.handleWith(askHandler).runST`.

**Turbolift row note:** `ReaderEffect.ask` is a `final override val` (`turbolift/effects/Reader.scala`), so
every site shares one operation node and there is no per-site operation identity to spread the profile.
What varies across the sixteen sites in Turbolift is the continuation class only. The row therefore
measures the portable half of what kyo's row measures, and its cell is labelled accordingly.

### 3.14 idleHandlerAddsNothing (Tier B, NarrowDepth)

The `fusionPastBudgetPaysRescuesOnly` chain run with an ambient installed but never read.

ZIO: the chain is `UIO[Int]`, the row is `runSync(ask.locally(1)(chain))`. This is the one row where the
per-run `locally` install is the point (it is what makes the handler present), so it is used here and only
here.
cats-effect: `runSync(ask.set(1).flatMap(_ => chain))`.
Turbolift: `chain.handleWith(askHandler).runST`, where `chain: Int !! Ask` by ascription exactly as
`KernelBench` writes `loop(0): Int < Ask`.
zio-blocks: **skip**, reason "no handler concept; the row would be byte-identical to
`fusionPastBudgetPaysRescuesOnly`".

### 3.15 statefulAnswersPaySuccessor (Tier B, Depth)

kernel2's clause is `[X] => (state, _) => Loop.continue(state + 1, 1: Int < Any)`: one operation that
answers `1` and advances the handler's state by one. Each library has a single primitive that does exactly
that, so the row stays one operation per level everywhere. Mind the tuple orders, they differ:

ZIO, `FiberRef.modify[B](f: A => (B, A)): UIO[B]`:

```scala
    @Benchmark
    def statefulAnswersPaySuccessor: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i)
            else st.modify(s => (1, s + 1)).flatMap(a => loop(i + a))
        runSync(loop(0))
    end statefulAnswersPaySuccessor
```

cats-effect, `IOLocal.modify[B](f: A => (A, B)): IO[B]`:

```scala
        else st.modify(s => (s + 1, 1)).flatMap(a => loop(i + a))
```

Turbolift, `StateSignature.update[A](f: S => (A, S)): A !! ThisEffect`:

```scala
    @Benchmark
    def statefulAnswersPaySuccessor: Int =
        def loop(i: Int): Int !! St =
            if i > Depth then !!.pure(i)
            else St.update(s => (1, s + 1)).flatMap(a => loop(i + a))
        loop(0).handleWith(stHandler).runST._1
    end statefulAnswersPaySuccessor
```

(`St.handler(0)` has output functor `(_, S)`, so the run yields `(Int, Int)`; `._1` is the answer, and
discarding `._2` matches kyo's `(_, a) => a` return clause.)

zio-blocks, the requirements' "local var loop", fidelity **LOW**:

```scala
    @Benchmark
    def statefulAnswersPaySuccessor: Int =
        var s = 0
        def loop(i: Int): Async[Int] =
            if i > Depth then Async.succeed(i)
            else
                s += 1
                answer.flatMap(a => loop(i + a))
        loop(0).block
    end statefulAnswersPaySuccessor
```

(the `var` is method-local, so no state crosses rows; `s` is dead after the loop, which is honest since
zio-blocks has no state effect to pay for. If the JIT removes the increment entirely the cell is
effectively `suspensionBaseline`; the note says so.)

### 3.16 handleLoopAnswersInPlace: skipped, and this is a deviation

The requirements group this row with `statefulAnswersPaySuccessor` in Tier B. The plan skips it, for all
four libraries, and flags the deviation for the user.

Reason: the kyo row differs from `suspensionBaseline` only in which kernel handler shape resolves the
operation (`handleCont`, whose clause receives the continuation and calls `cont(1)`, versus `handleLoop`,
which answers at the region's row). That distinction is internal to the kernel's handler surface. In ZIO,
cats-effect and zio-blocks there is no handler at all, so the row's user code would be byte-identical to
`suspensionBaseline`. In Turbolift both would be the same `Reader` operation under the same interpreter.
Four columns of a byte-identical duplicate row add noise and invite a reader to compare two cells that
were produced by the same code.

If the user wants the row kept, the only version that would carry signal is a Turbolift-only pair
contrasting a tail-resumptive interpreter clause (`!!.pure(1)`) against one that reifies the continuation
(`Control.capture(k => k(1))`, `turbolift/interpreter/Control.scala:34`). That is a new measurement rather
than a port of this row, so it is offered, not assumed.

### 3.17 trailingMapsStayLinear (Tier A shape, Tier B ambient, Depth)

Issue 531's shape. The claim under test (linear, not quadratic) is Tier A; the ambient read it is built
on is the Tier B substitution, so the cell carries both labels.

ZIO:

```scala
    @Benchmark
    def trailingMapsStayLinear: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i)
            else ask.get.flatMap(a => loop(i + a)).map(x => x)
        runSync(loop(0))
    end trailingMapsStayLinear
```

cats-effect / zio-blocks: same shape with their read and entry.
Turbolift: `Ask.ask.flatMap(a => loop(i + a)).map(x => x)`, handled and `runST`.

### 3.18 foreignCrossingsPayRotation (Tier B, Depth)

ZIO (two FiberRefs):

```scala
    @Benchmark
    def foreignCrossingsPayRotation: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i)
            else ask.get.flatMap(a => ask2.get.flatMap(t => loop(i + a + t)))
        runSync(loop(0))
    end foreignCrossingsPayRotation
```

cats-effect: two IOLocals, same shape.

Turbolift (exact: two Reader effects, two handlers, so the crossings are real):

```scala
    @Benchmark
    def foreignCrossingsPayRotation: Int =
        def loop(i: Int): Int !! (Ask & Ask2) =
            if i > Depth then !!.pure(i)
            else Ask.ask.flatMap(a => Ask2.ask.flatMap(t => loop(i + a + t)))
        loop(0).handleWith(askHandler).handleWith(ask2Handler).runST
    end foreignCrossingsPayRotation
```

zio-blocks: **skip**, per the requirements.

**ZIO/CE row note:** two FiberRefs (or two IOLocals) are two keys in one fiber-local map, not two nested
handler regions. Nothing is crossed and nothing is re-attached, so the row cannot exhibit rotation cost
for those two; it measures two ambient reads per level. Turbolift is the only column where the row means
what the kyo row means.

---

## 4. Run entries, and what each one includes

Per the requirements, run-entry overhead is part of what each library's synchronous execution costs and is
measured rather than factored out. Each bench class documents its entry in its scaladoc. Summary:

| library | entry | what one entry includes |
|---|---|---|
| kyo-kernel2 / old kernel | `.eval` | evaluator loop on the calling thread, no scheduler, no fiber |
| ZIO | `Unsafe.unsafe(implicit u => runtime.unsafe.run(z).getOrThrowFiberFailure())` on a shared `Runtime.default` | a `FiberRuntime` allocation, a per-run `FiberRefs.updatedAs` for `currentEnvironment`, registration in the fiber roots weak set (`RuntimeFlag.FiberRoots` is on by default), then synchronous execution on the calling thread; `runOrFork` returns `Right(exit)` without ever forking for an effect that never suspends asynchronously |
| cats-effect | `io.unsafeRunSync()` on `cats.effect.unsafe.implicits.global` | an `ArrayBlockingQueue(1)`, a fiber scheduled onto the work-stealing pool, and the calling thread parked in `scala.concurrent.blocking` until the pool hands the result back: **a thread handoff per operation** |
| zio-blocks | `.block` | for a settled value, a type-test chain and a cast; effectively free |
| Turbolift | `.handleWith(handler).runST` | a fresh `ZeroThreadedExecutor` per run (`Executor.ST` is `def zero() = new ZeroThreadedExecutor`), a root `FiberImpl`, then the CEK loop on the calling thread |

The spread between the cheapest entry (zio-blocks, ~free) and the dearest (cats-effect, a thread handoff)
is on the order of three decimal orders. That is a true property of the four synchronous entries and is
exactly what the requirements say to measure, but it means the fast rows are entry-dominated for CE. Two
mitigations, both proposed for user sign-off in section 6.

### 4.1 ZIO ambient: FiberRef, not environment. Resolved.

**Decision: `FiberRef.get` / `FiberRef.getWith`, with `ZIO.service` under `provideEnvironment` recorded as
the alternative and measured once as a sensitivity check.**

Justification, grounded in what the row measures. The kernel row is a per-suspension ambient read answered
by an installed handler. In ZIO:

- `provideEnvironment` **is** a FiberRef operation: `zio/ZIO.scala:1276`,
  `FiberRef.currentEnvironment.locallyWith(_ => r)(...)`. The environment is not a separate mechanism, it
  is one designated FiberRef.
- `ZIO.service[A]` is `serviceWith(identity)` (`zio/ZIO.scala:4777`), which reads that FiberRef **and then**
  performs a `ZEnvironment` typed-map lookup keyed by `LightTypeTag`. That lookup is ZIO's dependency
  dictionary, not its ambient-read machinery.

So `ZIO.service` measures `FiberRef.get` plus a dictionary lookup. A bare user `FiberRef` is the strictly
lower substrate and is the faster of the two natural spellings, which is what fairness rule 2 asks for.
Recording `ZIO.service` as the alternative and measuring it once keeps the choice auditable and answers the
open question with data as well as with reasoning.

Secondary reason: the environment spelling forces a non-`Any` `R` on every row's type, which would leak
into every signature and make the ZIO file diverge structurally from the other three for no measurement
benefit.

### 4.2 The ambient install

kyo installs its handler per run (`ArrowEffect.handleCont(...)` is applied inside the timed region).
Turbolift matches this exactly: the `Handler` is a prebuilt `val`, and `handleWith` is applied per run.

ZIO and cats-effect have no handler-application construct. The plan installs the ambient **by
construction** (the `FiberRef` / `IOLocal` default is the answer, and a fresh fiber reads the default), so
those two pay no per-run install. The asymmetry is stated in both class scaladocs and in the results
document. The alternative, a per-run scoped install (`ask.locally(1)(body)` for ZIO,
`ask.set(1).flatMap(_ => body)` for CE), is measured once as a sensitivity check on `suspensionBaseline`
and recorded; note that ZIO's `locally` is `ZIO.acquireReleaseWith(getAndSet(v))(set)(_ => zio)`
(`zio/FiberRef.scala:162`), a full uninterruptible bracket, which is why it is not the default choice.

`idleHandlerAddsNothing` is the one row where the per-run install is the substance of the row, so it uses
`locally` / `set` there (section 3.14).

### 4.3 cats-effect stateful row: IOLocal, not Ref. Deviation, flagged.

The requirements' Tier B table says `Ref[IO]` for cats-effect's stateful row. The plan uses
`IOLocal.modify` instead and flags the deviation.

Reason: kyo's `handleLoopState` threads state through the handler; the state is region-local and never
shared across fibers. `Ref[IO]` is a shared `AtomicReference` and its per-operation cost is a CAS on a
contended-capable cell, which measures a different thing. `IOLocal` is fiber-local state threading, which
is the same thing kyo's stateful region does, and it keeps the CE column consistent with the ZIO column
(`FiberRef.modify`), so the two are comparable to each other as well as to kyo.

`Ref[IO]` is measured once as the recorded alternative so the requirements' spelling is on record with a
number next to it.

---

## 5. Measurement execution

### 5.1 Protocol

Same as the campaign's: `-f 2 -wi 5 -i 5 -prof gc` for claims, `-f 3` for anything contested. Every table
reports `gc.alloc.rate.norm` beside time. `@OperationsPerInvocation(1000)` on the batch row in all four
classes, matching `KernelBench`.

Bench runs serialize on `/tmp/kyo-bench-mutex` (the standing rule in `reviews/KERNEL2-PERF-QUEUE.md:132`).
Never `sbt --batch`.

### 5.2 Session grouping

All four libraries measure back to back in **one** sbt session on the quiet machine, in a fixed order, so
no library gets a different machine state:

```
ZioBench -> CatsEffectBench -> ZioBlocksBench -> TurboliftBench
```

The kernel2 and old-kernel columns come from the existing gate2 JSONs
(`reviews/bench/kernel2-0821-gate2-kernel2-kb.json`, `reviews/bench/kernel2-0821-gate2-oldkernel.json`),
per the requirements' "reused as-is". Section 5.5 is the check that makes that reuse sound.

### 5.3 Commands

One JSON per class, so a rerun of one library never invalidates the others:

```sh
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"

sbt 'kyo-kernel2-bench-cross/Jmh/run -f 2 -wi 5 -i 5 -prof gc -rf json -rff reviews/bench/crosslib-<date>-zio.json        kyo.kernel.bench.cross.ZioBench'
sbt 'kyo-kernel2-bench-cross/Jmh/run -f 2 -wi 5 -i 5 -prof gc -rf json -rff reviews/bench/crosslib-<date>-ce.json         kyo.kernel.bench.cross.CatsEffectBench'
sbt 'kyo-kernel2-bench-cross/Jmh/run -f 2 -wi 5 -i 5 -prof gc -rf json -rff reviews/bench/crosslib-<date>-zioblocks.json  kyo.kernel.bench.cross.ZioBlocksBench'
sbt 'kyo-kernel2-bench-cross/Jmh/run -f 2 -wi 5 -i 5 -prof gc -rf json -rff reviews/bench/crosslib-<date>-turbolift.json  kyo.kernel.bench.cross.TurboliftBench'
```

Run inside one `sbt` invocation with the four commands sequenced (`sbt 'cmd1' 'cmd2' ...`) so the session
is genuinely shared; the four-line form above is shown separated only for readability.

Sensitivity checks, run after the main board and written to separate JSONs so they can never be mistaken
for the headline numbers:

```sh
# 4.1: ZIO environment spelling instead of FiberRef, suspensionBaseline only
sbt '... -rff reviews/bench/crosslib-<date>-alt-zio-env.json      kyo.kernel.bench.cross.ZioBench.suspensionBaseline'   # against a scratch variant
# 4.3: cats-effect Ref[IO] instead of IOLocal, statefulAnswersPaySuccessor only
# 5.4: cats-effect with tracing off
sbt '... -jvmArgsAppend -Dcats.effect.tracing.mode=none -rff reviews/bench/crosslib-<date>-alt-ce-notrace.json kyo.kernel.bench.cross.CatsEffectBench'
# 4.2: per-run scoped install, suspensionBaseline only, ZIO and CE
```

The two "scratch variant" checks (ZIO environment, CE `Ref`) need a temporary extra `@Benchmark` in the
class. Convention: name them `suspensionBaselineAltEnv` and `statefulAnswersPaySuccessorAltRef`, keep them
in the committed source with a scaladoc saying they are recorded alternatives and are excluded from the
headline table, and let the results script exclude anything whose name ends in `Alt<Something>`. That way
the alternative stays reproducible instead of being a deleted scratch edit.

### 5.4 cats-effect tracing

Default (`cached`) is the measured configuration, because it is what an idiomatic CE application runs.
`-Dcats.effect.tracing.mode=none` is measured once as the recorded alternative, since the default puts a
`getClass` plus a striped-hashtable lookup on every `map` and `flatMap` construction
(`cats/effect/tracing/TracingPlatform.scala:31-40`), which is a construction-time cost the kyo rows do not
have an analogue for.

### 5.5 Stack-flag soundness check (required before the join)

The cross project runs with `-Xss32m`; the gate2 JSONs were produced without it. The kernel rows are
trampolined and should be stack-insensitive, but "should be" is not evidence. Before joining, run:

```sh
sbt 'kyo-kernel2JVM/Jmh/run -f 2 -wi 5 -i 5 -prof gc -jvmArgsAppend -Xss32m -rf json -rff reviews/bench/crosslib-<date>-kernel2-xss-check.json kyo.kernel.bench.KernelBench'
```

and confirm every row matches its gate2 value inside the row's error bars. If any row moves, the joined
table is not sound and the kernel2 and old-kernel columns are re-measured under `-Xss32m` in the same
session as the cross board instead of being reused.

### 5.6 Results table

Deliverable: `reviews/CROSSLIB-BENCH-RESULTS.md`, and the raw JSONs committed under `reviews/bench/`.

Generation: a small `python3` script (JMH JSON is plain JSON; `reviews/bench/` already holds files in this
exact shape) that

1. reads the six JSONs: the two gate2 files plus the four cross files;
2. keys every record by the last dot-segment of `benchmark` (`kyo.kernel.bench.KernelBench.suspensionBaseline`
   and `kyo.kernel.bench.cross.ZioBench.suspensionBaseline` both key as `suspensionBaseline`), filtering the
   gate2 kernel2 file to records whose class is `KernelBench` (it also carries `ProtoKernelBench` rows);
3. pulls `primaryMetric.score`, `primaryMetric.scoreError`, `primaryMetric.scoreUnit` and
   `secondaryMetrics["gc.alloc.rate.norm"].score` (verified present and spelled without a leading
   interpunct in these files under JMH 1.37);
4. asserts `measurementIterations`, `warmupIterations` and `forks` agree across every file it joins, and
   fails loudly if they do not;
5. emits one row per benchmark name with six columns (kernel2, old kernel, ZIO, CE, zio-blocks,
   Turbolift), time and B/op in each cell, and fills a missing cell with its tier or skip reason from a
   table keyed by (row, library) that mirrors section 3.0 and the per-row notes;
6. drops any benchmark whose name matches the `Alt` suffix convention from 5.3.

The script lives with the results document, not in `scripts/` (it is a one-board reporting tool, not build
infrastructure).

The results document leads with the fidelity legend (exact / substituted-with-note / LOW / NONE / skipped),
before any number, so the tiering is read before the table rather than after it.

---

## 6. Two additions proposed for user sign-off

Neither is in the requirements; both are proposed rather than assumed, and the plan is executable without
them.

**6a. An explicit entry-floor row.** The entry costs in section 4 span three decimal orders, so on the fast
rows the CE and Turbolift cells are mostly entry. A single extra row per class, `entryFloorBatch`, with
`@OperationsPerInvocation(1000)` and a body that runs `pure(seed + i)` through the entry with no
transformation at all, makes the floor visible in the same units as every other row, so a reader can see
what a cell contains without any number being factored out of it. Cost: one row per class, plus a matching
row added to `KernelBench` and the old-kernel mirror and a re-run of both boards (about two extra board
runs) if the kernel columns are to have that cell filled.

**6b. Keeping `handleLoopAnswersInPlace` as a Turbolift-only continuation-shape pair.** Section 3.16
explains why the row as written cannot port. If the underlying question (what does reifying the
continuation cost versus resuming in place) is wanted, the Turbolift `Control.capture(k => k(1))` versus
`!!.pure(1)` pair answers it, in one column, as a new named row rather than as a port.

---

## 7. Risks and mitigations

**R1. zio-blocks has no trampoline; the depth-10000 rows recurse on the JVM stack.**
Established in 0.2 from the source, not suspected. Without headroom the affected rows
(`deepRecursionPaysRescuesOnly`, `suspensionBaseline`, `suspensionFusesContinuation`,
`sharedHandlerPaysDispatch`, `trailingMapsStayLinear`) throw `StackOverflowError`.
*Mitigation:* `Jmh / javaOptions += "-Xss32m"` on the cross project, applied uniformly to all four classes
so no library gets a different JVM, plus the soundness check in 5.5 before the kernel columns are joined
in. The cost model difference is reported as a finding in the results document, not hidden by the flag.

**R2. cats-effect's entry is a thread handoff and will dominate its fast rows.**
Established in 0.5 from `IOPlatform.scala:70-82`.
*Mitigation:* the entry is documented in the class scaladoc and in the section 4 table; proposal 6a makes
the floor measurable; `-Dcats.effect.tracing.mode=none` (5.4) separates the construction-time tracing cost
from the entry cost. No number is factored out. If a CE cell comes back within noise of its own entry
floor, the results document says "at the entry floor" rather than printing a ratio that is really a
scheduler measurement.

**R3. zio-blocks is pre-1.0 and API-unstable.**
`0.0.51`, six published versions of `zio-blocks-async_3` (0.0.46 through 0.0.51, first published within the
last few weeks), and the repo carries ZIO's "Project Stage: Development" badge, which in ZIO's own taxonomy
means the API may change.
*Mitigation:* pin the exact version in a named val (`zioBlocksVersion`), record the resolved version in the
results document header, and commit the raw JSON so the numbers stay attributable to that exact artifact.
The API surface actually used is four members (`succeed`, `map`, `flatMap`, `block`), all of which are the
module's headline API and the ones its own README example uses, which is the smallest possible exposure to
drift.

**R4. Turbolift's default run mode is multi-threaded.**
`Mode.default = MT` (`turbolift/mode/Mode.scala`). Calling `.run` instead of `.runST` would ship every
operation to a thread pool and measure a handoff.
*Mitigation:* `runST` everywhere, stated in the class scaladoc, plus a review checklist item that no `.run`
appears in `TurboliftBench.scala`. A grep for `\.run\b` in that file is part of the pre-commit check.
Secondary note recorded in the same scaladoc: `Executor.ST` is `def zero() = new ZeroThreadedExecutor`, so
`runST` allocates one executor per run and that allocation is part of the measured entry.

**R5. Dependency eviction against kyo's own resolution.**
Both new artifacts declare `scala3-library_3:3.3.7`; zio-blocks-async also pulls
`zio-blocks-combinators_3` and `dotty-cps-async_3:1.3.4`.
*Mitigation:* the eviction is confined to this one unpublished project, which nothing depends on. sbt will
evict 3.3.7 in favour of the build's 3.8.4, which is correct. If the eviction warning is noisy, add
`evictionErrorLevel := Level.Info` **scoped to this project only**, never build-wide. ZIO and cats-effect
are already at the build's own pins (`zioVersion`, `catsVersion`), so they introduce no new conflict at
all.

**R6. `-Werror` against foreign inline expansions.**
`kyo-settings` sets `-Werror` plus `-feature`, `strictEquality` and the discard/pure-statement `-Wconf`.
Turbolift's `map` / `flatMap` are `inline` methods that instantiate anonymous classes and carry
`@nowarn("msg=anonymous class definition")` at their own definition sites; ZIO ships an implicit conversion
(`implicitFunctionIsFunction`) that a `-feature` build can flag.
*Mitigation:* the `-Wconf:msg=anonymous class definition:silent` line in section 1, and the `Unsafe.unsafe
{ implicit u => ... }` spelling everywhere (a direct `Unsafe => A` lambda, so no implicit conversion is
involved). If neither warning materialises, delete the `-Wconf` line rather than leave a dead suppression.
No `==` on user types appears in any body, so `strictEquality` is satisfied without a `CanEqual`.

**R7. The prebuilt-chain row is degenerate for zio-blocks.**
`fusionAfterSuspensionRunOnly` folds at field initialisation there (3.11).
*Mitigation:* report the cell with fidelity NONE and the one-line reason, rather than omitting it or
printing a number that looks like a win. The same care applies to the zio-blocks
`statefulAnswersPaySuccessor` `var`, which the JIT may remove entirely (3.15).

**R8. Row-name collision in the join.**
The kernel2 gate2 file carries both `KernelBench` and `ProtoKernelBench` records, and both kernel boards
use the identical FQN `kyo.kernel.bench.KernelBench.*`, so the two kernels are distinguished only by which
file a record came from.
*Mitigation:* the results script keys by (source file, class simple name, method name) internally and only
collapses to the method name at render time; step 4 of 5.6 asserts the JMH settings match across files
before it joins anything.

**R9. Machine state drift between the cross board and the reused gate2 numbers.**
The gate2 JSONs were produced in an earlier session.
*Mitigation:* 5.5 already re-runs `KernelBench` under the cross project's stack flag; comparing that re-run
against gate2 doubles as a machine-drift check. If the re-run disagrees with gate2 outside the error bars
for reasons other than the flag, the kernel columns are re-measured in the cross board's own session and
the reuse is abandoned.

---

## 8. What could not be satisfied as written

1. **Turbolift 0.114.0 is stale.** Latest is 0.126.0 (0.114.0 is nine releases back). The plan pins
   0.126.0. If the user wants 0.114.0 specifically, say so; the API used here is present in both.
2. **`handleLoopAnswersInPlace` cannot be ported** as a distinct row (3.16). Skipped with the reason, and
   an alternative offered in 6b.
3. **cats-effect's stateful row uses `IOLocal`, not `Ref[IO]`** as the requirements' table specifies
   (4.3). `Ref[IO]` is measured once as the recorded alternative.
4. **`deepRecursionNoRescue` and `deepRecursionOneRescue` do not exist on `KernelBench`.** They are
   `ProtoKernelBench` rows. Only `deepRecursionPaysRescuesOnly` is ported.
5. **`foreignCrossingsPayRotation` measures rotation only for Turbolift.** ZIO and cats-effect have no
   nested handler regions to cross; their cells measure two ambient reads per level and are labelled that
   way.
6. **`fusionAfterSuspensionRunOnly` is degenerate for zio-blocks** (3.11), and `idleHandlerAddsNothing` has
   no zio-blocks analogue at all (3.14).
7. **`idleHandlerAddsNothing` was unclassified** in the requirements' tier tables; the plan assigns it
   Tier B.
