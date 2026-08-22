# Cross-library API ground truth

Verified 2026-08-21 directly against the published artifacts on Maven Central (poms fetched, sources
jars unpacked and read). This is the reference the implementation plan is validated against; where
the plan and this document disagree, the sources jar wins.

## Artifact existence (HTTP 200 on repo1.maven.org)

| artifact | version | status |
|---|---|---|
| `dev.zio %% zio` | 2.1.26 | exists |
| `org.typelevel %% cats-effect` | 3.6.3 latest 3.6.x; 3.7.0 stable exists | exists |
| `dev.zio %% zio-blocks-async` | 0.0.51 | exists |
| `io.github.marcinzh %% turbolift-core` | 0.114.0 | exists |

All `_3` suffixed: plain Scala 3 artifacts, no `CrossVersion.for3Use2_13` needed anywhere.

## zio-blocks Async (from zio-blocks-async_3-0.0.51-sources.jar)

- `type Async[+A]` lives in `package object zio.blocks.async`; import `zio.blocks.async.*` brings
  the type and the extension methods.
- Constructors on `object Async`: `succeed[A](a: A)`, `fail(cause: Throwable)`, `attempt`,
  `promise`, `start`. A bare `A` is NOT an `Async[A]` (encoding: a completed success IS the value,
  wrapped only when the value is itself Async-like).
- Extensions (all `inline`, ready-value fast path spliced at the call site):
  `map`, `flatMap`, `catchAll`, `zipWith`, `tap`, `ensuring`, `foldCause`, `either`, `as`, `unit`.
- Sync entry: `inline def block: A` (parks between polls; throws the failure cause). `await` also
  exists with different JS semantics; `block` is the bench entry.
- **Restricted monad**: `Async[Async[A]]` is unsupported; success values must not be Async.
  Consequence for the port: kyo's flattening `.map(_ => loop(i + 1))` recursion steps must be
  `.flatMap(_ => loop(i + 1))` here (and in ZIO/CE, where map also does not flatten).
- **No trampoline on the ready path**: `flatMap` over a ready value applies the continuation
  immediately, inline, on the caller's stack. Settled recursion at Depth=10000 therefore consumes
  real stack frames; whether the deep rows survive is an empirical question (JMH forks run with the
  Test javaOptions stack size). If they overflow, that is a finding to record, not a row to force.
- No effect system, no environment, no handler concept: Tier B rows are LOW fidelity by
  construction, as the requirements already state.

## Turbolift (from turbolift-core_3-0.114.0-sources.jar)

- `type !![+A, -U] = Computation[A, U]`; `!!.pure(a): A !! Any`; `map`/`flatMap` are
  `final inline def` on `Computation`.
- Effect declaration: `case object AskFx extends Reader[Int]` (`trait Reader[R] extends
  ReaderEffect[R]`, which extends `Effect[ReaderSignature[R]]`). Operation: `AskFx.ask: Int !!
  AskFx.type`. `Reader` exports its default handler as `handler`: `AskFx.handler(initial)`.
- State: `case object St extends State[Int]`; ops `St.get`, `St.put`, `St.modify`,
  `St.getModify(f)`. `State` exports `handlers.local` as `handler`; the local handler's output
  functor is `(_, S)`: handling returns `(A, S)`, take `._1`.
- Handling: `comp.handleWith[V](h)` where `V` is the row remaining after elimination
  (`HandleWithSyntax.apply` demands `(L & V) <:< U`). Single effect: `comp.handleWith[Any](
  AskFx.handler(1)).run`. Two effects eliminate one at a time:
  `comp.handleWith[B.type](A.handler(1)).handleWith[Any](B.handler(0)).run`.
- Run: `def run(using Mode = Mode.default): A` on a fully-handled (`U = Any`) computation;
  `runSync` executor, `.get` on the internal `Outcome` (throws on failure). No IO effect needed for
  pure computations.

## zio-blocks own benchmark survey (async-benchmarks module, GitHub main)

Their suite already benches zb against kyo, cats-effect, and parasitic Future, with the hygiene rule
"every input is read from a @State field, never a literal". Classes and verdicts for adoption into
the cross-library boards:

| class | shape | verdict |
|---|---|---|
| AsyncChainBench mapN/flatMapN | build a chain of N map/flatMap nodes DYNAMICALLY (`fa = fa.map(_ + 1)` in a runtime loop over a settled value), run once | **ADOPT**: KernelBench has no settled dynamic-accumulation row, and this is exactly kernel2's Chain/AndThen accumulate-then-fold machinery. Two new rows on all six boards, N = NarrowDepth = 1000 |
| AsyncBench succeed/map1/flatMap1 | single op on a settled value | skip: evalFixedOverheadBatch already covers the fixed floor with better resolution |
| AsyncErrorBench | fail/catchAll/attempt/foldCause | skip: kyo's error channel (Abort) is prelude-level, not a kernel surface; the row would compare different layers |
| AsyncSuspendedPollBench | chains over a promise-suspended value completed later | skip: fusionAfterSuspension/RunOnly covers chain-over-unanswered-suspension at comparable fidelity |
| AsyncTrueAsyncBench, AsyncStartBench, AsyncScalingBench | true-async round trips, fiber start, thread parks | skip: the comparison is synchronous evaluation; kernel2 has no scheduler integration yet |
| AsyncBlockBench | direct-style async/await macro shapes | skip: different programming model, no kernel2 analogue |

Session consequence of the two adopted rows: they must be added to kyo-kernel2's KernelBench and
the old-kernel mirror as well, which forces fresh same-session runs of both kyo boards alongside
the four external boards (the kernel skill's same-session rule; the gate2 JSONs remain the
regression record, the comparison uses the fresh session).

## ZIO 2.1.26 / cats-effect (well-known surfaces, to confirm at compile time)

- ZIO: `ZIO.succeed`, `.map`, `.flatMap`; sync entry `Unsafe.unsafe(implicit u =>
  Runtime.default.unsafe.run(zio).getOrThrowFiberFailure())`. Ambient-value candidates for the Ask
  rows: `ZIO.service[Int]` under `provideEnvironment` vs `FiberRef.get`; the plan picks one with
  justification and the bench scaladoc records the substitution.
- cats-effect: `IO.pure`, `.map`, `.flatMap`, `io.unsafeRunSync()` with
  `import cats.effect.unsafe.implicits.global`; `IOLocal` for the Ask substitution, `Ref[IO]` /
  local var for stateful.
