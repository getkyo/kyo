package kyo.test.prop

import kyo.<
import kyo.Abort
import kyo.Async
import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Scope
import kyo.test.SuiteFingerprintMarker
import kyo.test.internal.TestBase
import kyo.test.prop.Gen
import kyo.test.prop.PropertyFailedException
import kyo.test.prop.internal.Seed
import kyo.test.prop.internal.Tree

/** Implementation base that adds forAll property-based testing on top of [[kyo.test.internal.TestBase]] WITHOUT the
  * [[kyo.test.SuiteFingerprintMarker]] mixin.
  *
  * This base exists so the deliberately-failing fixture suites in the prop test sources (driven via `TestRunner.runToFuture`, never run
  * standalone) can extend it directly and stay out of sbt test discovery. The public [[PropertyTest]] subclass adds the marker so real user
  * property suites are discovered; the fixtures must not be, because they fail by design. JVM discovery (Zinc's `xsbt.api.Discovery`) does
  * not surface a `PropertyTest[Any]` fixture as a standalone suite, but Scala Native's reflective discovery does (it matches the marker on
  * the actual class hierarchy via `@EnableReflectiveInstantiation`), which previously failed the Native task. Extending this
  * marker-free base keeps the fixtures instantiable by `runToFuture` while invisible to discovery on every platform.
  *
  * forAll is inline so it integrates with the Test DSL. The test name is synthesized from the Frame source location. Each forAll call runs
  * numSamples iterations with a size schedule that grows from 1 to 100. On first failure, the shrink loop minimizes the counterexample
  * before reporting.
  *
  * The leaf body registered by forAll has type `Unit < (S & Async & Abort[Throwable] & Scope)`, matching the baseline established by
  * TestBase[S]. The suite may carry an extra effect row S for scenario concerns (e.g. determinism via Random.withSeed) discharged via
  * .handle at the suite or leaf level; the framework has zero awareness of scenario concerns per design A.4.
  *
  * The DSL methods in this class use `protected` visibility because the framework contract is inheritance-based; this is a deliberate
  * exception to the `No protected` convention (CONTRIBUTING P5), documented here as permitted for abstract DSL base classes.
  *
  * @tparam S
  *   the additive extra effect row a suite's leaf bodies may use beyond the always-present baseline
  * @see
  *   [[PropertyTest]] the public, discoverable subclass that adds the SuiteFingerprintMarker mixin
  * @see
  *   [[kyo.test.prop.Gen]] the generator type passed to forAll
  * @see
  *   [[kyo.test.prop.Shrink]] algorithms used by the shrink loop inside forAll
  * @see
  *   [[kyo.test.prop.PropertyFailedException]] thrown by forAll when shrinking is complete and the property still fails
  */
abstract class PropertyTestBase[S] extends TestBase[S]:

    /** Number of samples to run per property (default: 100). Override to increase/decrease. */
    protected def numSamples: Int = 100

    /** Maximum number of shrink iterations per failure (default: 100). */
    protected def maxShrinks: Int = 100

    /** The seed used for non-randomized runs (default: 42L). Override for a fixed alternate seed. */
    protected def nonRandomSeed: Long = 42L

    /** Compute the per-forAll-call RNG seed: the randomSeed inherited from TestBase when randomize is true; nonRandomSeed otherwise.
      */
    private def rngSeed: Long = if randomize then randomSeed else nonRandomSeed

    // ── Internal helpers ─────────────────────────────────────────────────

    /** Run the property body for a single sample; returns Maybe[Throwable] (Absent on success).
      *
      * The body is a Kyo computation Unit < (S & Async & Abort[Throwable] & Scope). Abort.run[Throwable] catches any thrown AssertionFailed
      * (and other Throwable) at this boundary, surfacing it as a Result.Failure. This matches the runner's failure channel: a leaf body's
      * thrown AssertionFailed becomes a Result.Failure rather than escaping as a raw throwable.
      */
    private[prop] def tryBody[A](
        sample: A,
        body: A => Unit < (S & Async & Abort[Throwable] & Scope)
    )(using Frame): Maybe[Throwable] < (S & Async & Scope) =
        Abort.run[Throwable](body(sample)).map {
            case kyo.Result.Success(_) => Maybe.empty
            case kyo.Result.Failure(t) => Maybe(t)
            case kyo.Result.Panic(t)   => Maybe(t)
        }
    end tryBody

    /** Walk candidates; return the first one whose body fails, with its cause. */
    private[prop] def tryFirstFailing[A](
        candidates: Chunk[A],
        body: A => Unit < (S & Async & Abort[Throwable] & Scope)
    )(using Frame): Maybe[(A, Throwable)] < (S & Async & Scope) =
        if candidates.isEmpty then Maybe.empty
        else
            val head = candidates(0)
            val tail = candidates.drop(1)
            tryBody(head, body).flatMap {
                case Present(t) => Maybe((head, t))
                case Absent     => tryFirstFailing(tail, body)
            }
    end tryFirstFailing

    /** Walk a tree's immediate children depth-first, returning the first child whose body still fails (with the child's tree and cause).
      */
    private[prop] def tryFirstFailingTree[A](
        children: LazyList[Tree[A]],
        body: A => Unit < (S & Async & Abort[Throwable] & Scope)
    )(using Frame): Maybe[(Tree[A], Throwable)] < (S & Async & Scope) =
        if children.isEmpty then Maybe.empty
        else
            val head = children.head
            tryBody(head.value, body).flatMap {
                case Present(t) => Maybe((head, t))
                case Absent     => tryFirstFailingTree(children.tail, body)
            }
    end tryFirstFailingTree

    /** Shrink loop over a rose tree: given a failing node, take the first child whose value still fails and recurse into it, until no child
      * fails. That node's value is the minimal counterexample (greedy best-first over the tree).
      */
    private def shrinkTree[A](
        failing: Tree[A],
        body: A => Unit < (S & Async & Abort[Throwable] & Scope),
        cause: Throwable,
        remaining: Int
    )(using Frame): (A, Throwable) < (S & Async & Scope) =
        if remaining <= 0 then (failing.value, cause)
        else
            tryFirstFailingTree(failing.shrinks(), body).flatMap {
                case Present((smaller, t)) => shrinkTree(smaller, body, t, remaining - 1)
                case Absent                => (failing.value, cause)
            }
    end shrinkTree

    /** Core forAll execution: a Kyo computation that completes with a PropertyFailedException throw on first failure after shrinking, or
      * Unit on all-pass.
      *
      * The RNG is a pure splittable Seed (SplitMix64) constructed from the `seed` parameter. For plain `forAll` calls the seed is
      * `rngSeed` (randomSeed when randomize is true, nonRandomSeed otherwise). For `forAllSeeded` calls the seed is the explicit Long
      * supplied by the caller. A fixed `(seed, size)` pair always produces the same Tree (determinism). The Seed is unrelated to kyo
      * Random. Deterministic scenarios that need kyo Random are a scenario concern flowing through S via .handle(Random.withSeed(seed)),
      * per design A.4 (02-design.md:196-218).
      */
    private[prop] def executeForAll[A](
        gen: Gen[A],
        body: A => Unit < (S & Async & Abort[Throwable] & Scope),
        frame: Frame,
        seed: Long
    )(using Frame, kyo.test.AssertScope): Unit < (S & Async & Abort[Throwable] & Scope) =
        summon[kyo.test.AssertScope].recordEvaluated()
        val n = numSamples
        def loop(i: Int, seedState: Seed): Unit < (S & Async & Abort[Throwable] & Scope) =
            if i >= n then ()
            else
                val size               = 1 + (i * 100 / n)
                val (sampleSeed, next) = Seed.split(seedState)
                val tree               = gen.sample(sampleSeed, size)
                tryBody(tree.value, body).flatMap {
                    case Absent =>
                        loop(i + 1, next)
                    case Present(t) =>
                        shrinkTree(tree, body, t, maxShrinks).flatMap { case (shrunk, shrunkCause) =>
                            Abort.fail(new PropertyFailedException(tree.value, shrunk, shrunkCause, seed)(using frame))
                        }
                }
        loop(0, Seed(seed))
    end executeForAll

    // ── forAll arity 1 ───────────────────────────────────────────────────

    /** Run a property over numSamples samples from gen.
      *
      * On first failure, the counterexample is shrunk toward the minimal failing value before being reported.
      *
      * @param gen
      *   the generator for the sample values
      * @param body
      *   the property body; a plain Unit body auto-lifts; an Async body is accepted through the baseline row
      */
    protected inline def forAll[A](
        inline gen: Gen[A]
    )(inline body: kyo.test.AssertScope ?=> A => Unit < (S & Async & Abort[Throwable] & Scope))(using inline frame: Frame): Unit =
        val testName = s"forAll @ ${frame.position.fileName}:${frame.position.lineNumber}"
        regCtx.visitLeaf(testName, (as: kyo.test.AssertScope) ?=> executeForAll(gen, body(using as), frame, rngSeed))
    end forAll

    // ── forAll arity 2 ───────────────────────────────────────────────────

    /** Run a property with two independent generators, zipped into a tuple. */
    protected inline def forAll[A, B](
        inline genA: Gen[A],
        inline genB: Gen[B]
    )(inline body: kyo.test.AssertScope ?=> (A, B) => Unit < (S & Async & Abort[Throwable] & Scope))(using inline frame: Frame): Unit =
        val testName = s"forAll @ ${frame.position.fileName}:${frame.position.lineNumber}"
        val zipped   = Gen.zip(genA, genB)
        regCtx.visitLeaf(
            testName,
            (as: kyo.test.AssertScope) ?=> executeForAll(zipped, (t: (A, B)) => body(using as)(t._1, t._2), frame, rngSeed)
        )
    end forAll

    // ── forAll arity 3 ───────────────────────────────────────────────────

    /** Run a property with three independent generators. */
    protected inline def forAll[A, B, C](
        inline genA: Gen[A],
        inline genB: Gen[B],
        inline genC: Gen[C]
    )(inline body: kyo.test.AssertScope ?=> (A, B, C) => Unit < (S & Async & Abort[Throwable] & Scope))(using inline frame: Frame): Unit =
        val testName = s"forAll @ ${frame.position.fileName}:${frame.position.lineNumber}"
        val zipped   = Gen.zip3(genA, genB, genC)
        regCtx.visitLeaf(
            testName,
            (as: kyo.test.AssertScope) ?=> executeForAll(zipped, (t: (A, B, C)) => body(using as)(t._1, t._2, t._3), frame, rngSeed)
        )
    end forAll

    // ── forAll arity 4 ───────────────────────────────────────────────────

    /** Run a property with four independent generators. */
    protected inline def forAll[A, B, C, D](
        inline genA: Gen[A],
        inline genB: Gen[B],
        inline genC: Gen[C],
        inline genD: Gen[D]
    )(inline body: kyo.test.AssertScope ?=> (A, B, C, D) => Unit < (S & Async & Abort[Throwable] & Scope))(using
        inline frame: Frame
    ): Unit =
        val testName = s"forAll @ ${frame.position.fileName}:${frame.position.lineNumber}"
        val zipped   = Gen.zip4(genA, genB, genC, genD)
        regCtx.visitLeaf(
            testName,
            (as: kyo.test.AssertScope) ?=>
                executeForAll(zipped, (t: (A, B, C, D)) => body(using as)(t._1, t._2, t._3, t._4), frame, rngSeed)
        )
    end forAll

    // ── forAllSeeded replay entry point (arities 1-4) ────────────────────

    /** Run a property exactly like [[forAll]], but force the per-call RNG seed to the explicit `seed`, overriding the suite-level
      * randomize/nonRandomSeed setting. Pair with [[kyo.test.prop.PropertyFailedException.seed]]: copy a failing seed from a report and
      * pin it here to reproduce the exact failing run deterministically. Per-call and local: it does not change nonRandomSeed's default
      * or affect sibling forAll calls.
      *
      * @param seed
      *   the explicit seed to use for this property run; overrides rngSeed entirely
      * @param gen
      *   the generator for the sample values
      * @param body
      *   the property body; a plain Unit body auto-lifts; an Async body is accepted through the baseline row
      */
    protected inline def forAllSeeded[A](inline seed: Long, inline gen: Gen[A])(
        inline body: kyo.test.AssertScope ?=> A => Unit < (S & Async & Abort[Throwable] & Scope)
    )(using inline frame: Frame): Unit =
        val testName = s"forAll @ ${frame.position.fileName}:${frame.position.lineNumber}"
        regCtx.visitLeaf(testName, (as: kyo.test.AssertScope) ?=> executeForAll(gen, body(using as), frame, seed))
    end forAllSeeded

    /** [[forAllSeeded]] with two independent generators, zipped into a tuple. */
    protected inline def forAllSeeded[A, B](inline seed: Long, inline genA: Gen[A], inline genB: Gen[B])(
        inline body: kyo.test.AssertScope ?=> (A, B) => Unit < (S & Async & Abort[Throwable] & Scope)
    )(using inline frame: Frame): Unit =
        val testName = s"forAll @ ${frame.position.fileName}:${frame.position.lineNumber}"
        val zipped   = Gen.zip(genA, genB)
        regCtx.visitLeaf(
            testName,
            (as: kyo.test.AssertScope) ?=> executeForAll(zipped, (t: (A, B)) => body(using as)(t._1, t._2), frame, seed)
        )
    end forAllSeeded

    /** [[forAllSeeded]] with three independent generators. */
    protected inline def forAllSeeded[A, B, C](
        inline seed: Long,
        inline genA: Gen[A],
        inline genB: Gen[B],
        inline genC: Gen[C]
    )(
        inline body: kyo.test.AssertScope ?=> (A, B, C) => Unit < (S & Async & Abort[Throwable] & Scope)
    )(using inline frame: Frame): Unit =
        val testName = s"forAll @ ${frame.position.fileName}:${frame.position.lineNumber}"
        val zipped   = Gen.zip3(genA, genB, genC)
        regCtx.visitLeaf(
            testName,
            (as: kyo.test.AssertScope) ?=> executeForAll(zipped, (t: (A, B, C)) => body(using as)(t._1, t._2, t._3), frame, seed)
        )
    end forAllSeeded

    /** [[forAllSeeded]] with four independent generators. */
    protected inline def forAllSeeded[A, B, C, D](
        inline seed: Long,
        inline genA: Gen[A],
        inline genB: Gen[B],
        inline genC: Gen[C],
        inline genD: Gen[D]
    )(
        inline body: kyo.test.AssertScope ?=> (A, B, C, D) => Unit < (S & Async & Abort[Throwable] & Scope)
    )(using inline frame: Frame): Unit =
        val testName = s"forAll @ ${frame.position.fileName}:${frame.position.lineNumber}"
        val zipped   = Gen.zip4(genA, genB, genC, genD)
        regCtx.visitLeaf(
            testName,
            (as: kyo.test.AssertScope) ?=> executeForAll(zipped, (t: (A, B, C, D)) => body(using as)(t._1, t._2, t._3, t._4), frame, seed)
        )
    end forAllSeeded

end PropertyTestBase

/** Base class that adds forAll property-based testing to any kyo-test V3 (next) suite.
  *
  * Usage: extend PropertyTest[S] (instead of Test[S] directly) and call forAll in the class body.
  *
  * Carries the [[kyo.test.SuiteFingerprintMarker]] mixin (via [[PropertyTestBase]] plus the marker here) so sbt's `SubclassFingerprint`
  * discovery picks up user property suites, exactly as [[kyo.test.Test]] does for plain suites. The forAll DSL and shrink machinery live on
  * [[PropertyTestBase]]; this subclass adds only the discovery marker.
  *
  * @tparam S
  *   the additive extra effect row a suite's leaf bodies may use beyond the always-present baseline
  * @see
  *   [[PropertyTestBase]] the marker-free implementation base (used by non-discoverable internal fixtures)
  * @see
  *   [[kyo.test.Test]] the plain-suite base class analogous to this one
  */
abstract class PropertyTest[S] extends PropertyTestBase[S] with SuiteFingerprintMarker
