package kyo.compat

import kyo.*
import org.scalactic.Prettifier

/** Soundness and portability tests for the holder-based `CStream` (kyo binding only).
  *
  * The point of the holder design is that element-preserving operators carry no `Tag` requirement, so a library author can write a pipeline
  * generic in the element type and have it compile against the kyo binding exactly as it does against the others. These tests pin that down,
  * and pin down the channel-isolation soundness that the tag (not an erased `Any` key) provides.
  */
class CStreamTagFreeTest extends CompatTest:

    // CompatTest mixes in NonImplicitAssertions, which suppresses the ambient Prettifier that
    // assertCompiles/assertDoesNotCompile require; reinstate it locally.
    given Prettifier = Prettifier.default

    // --- Portability: generic-element pipelines compile with NO Tag bound ---
    // Each helper below is generic in `A` and uses NO `using Tag[...]`. Before the holder design these would not
    // compile (kyo.Stream re-summons Tag[Emit[Chunk[A]]] per operator); now they do. Their mere compilation is the
    // regression guard; the assertions confirm they also behave correctly.

    def preserve[A](s: CStream[A]): CStream[A] =
        s.take(5).drop(1).filterPure(_ => true)

    def collectAll[A](s: CStream[A]): CIO[CChunk[A]] =
        s.run

    def count[A](s: CStream[A]): CIO[Int] =
        s.foldPure(0)((n, _) => n + 1)

    def duplicate[A](s: CStream[A]): CStream[A] =
        s.concat(s)

    "generic element-preserving pipeline runs without a Tag bound" in run {
        preserve(CStream.init(Seq(1, 2, 3, 4, 5, 6, 7))).run.map(c => assert(c.toSeq == Seq(2, 3, 4, 5)))
    }

    "generic element-preserving pipeline works for a non-Int element type" in run {
        preserve(CStream.init(Seq("a", "b", "c", "d", "e", "f"))).run.map(c => assert(c.toSeq == Seq("b", "c", "d", "e")))
    }

    "generic terminal run works without a Tag bound" in run {
        collectAll(CStream.init(Seq(10, 20, 30))).map(c => assert(c.toSeq == Seq(10, 20, 30)))
    }

    "generic foldPure-based count works without a Tag bound" in run {
        count(CStream.init(Seq("x", "y", "z", "w"))).map(n => assert(n == 4))
    }

    "generic concat works without a Tag bound" in run {
        duplicate(CStream.init(Seq(1, 2))).run.map(c => assert(c.toSeq == Seq(1, 2, 1, 2)))
    }

    // --- Soundness: distinct element channels never cross-talk ---
    // If the holder keyed its channel with an erased `Tag[Emit[Chunk[Any]]]`, a flatMap from Int to String would let
    // the handler greedily capture both channels. With the honest per-element tag, only the intended channel matches.

    "flatMap from Int to String emits only the String channel" in run {
        CStream.init(Seq(1, 2))
            .flatMap(i => CStream.init(Seq(s"a$i", s"b$i")))
            .run.map(c => assert(c.toSeq == Seq("a1", "b1", "a2", "b2")))
    }

    "nested flatMap Int -> String -> Boolean keeps only the final channel" in run {
        CStream.init(Seq(1, 2))
            .flatMap(i => CStream.init(Seq(s"v$i")))
            .flatMap(s => CStream.init(Seq(s.length % 2 == 0, s.length % 2 == 1)))
            .run.map(c => assert(c.toSeq == Seq(true, false, true, false)))
    }

    "map changing element type to String, then back to length, stays isolated" in run {
        CStream.init(Seq(1, 22, 333))
            .map(i => CIO.value(i.toString))
            .mapPure(_.length)
            .run.map(c => assert(c.toSeq == Seq(1, 2, 3)))
    }

    // --- Soundness: lower returns a faithful native stream after an element change ---
    // The stored tag is always honest, so lowering a mapped CStream yields a native kyo.Stream whose emit channel
    // matches the honest output tag. Running it natively (with a freshly summoned tag) must see every element.

    "lower after an element-changing map yields a native stream that runs faithfully" in run {
        val native: kyo.Stream[String, Abort[Throwable] & Async] =
            CStream.init(Seq(1, 2, 3)).map(i => CIO.value(s"x$i")).lower
        CIO.lift(native.run).map(chunk => assert(chunk.toSeq == Seq("x1", "x2", "x3")))
    }

    "lift(native).run round-trips a hand-built native stream" in run {
        val native = kyo.Stream.init(Seq(7, 8, 9))
        CStream.lift(native).run.map(c => assert(c.toSeq == Seq(7, 8, 9)))
    }

    // --- Characterization: the B1 boundary and invariance, asserted at the type level ---

    "element-preserving operators need no Tag, even fully generic" in {
        assertCompiles("def f[A](s: CStream[A]): CStream[A] = s.take(1).drop(0).filterPure(_ => true)")
    }

    "a fully-generic terminal needs no Tag" in {
        assertCompiles("def f[A](s: CStream[A]): CIO[CChunk[A]] = s.run")
    }

    "mapping to a fully-abstract element type without its Tag does NOT compile" in {
        // B1: the stored A-tag cannot name B; the output Tag must be supplied (it derives automatically for concrete B).
        assertDoesNotCompile("def f[A, B](s: CStream[A])(g: A => CIO[B]): CStream[B] = s.map(g)")
    }

    "mapping to an abstract element type WITH its Tag compiles" in {
        assertCompiles(
            "def f[A, B](s: CStream[A])(g: A => CIO[B])(using kyo.Tag[kyo.Emit[kyo.Chunk[B]]]): CStream[B] = s.map(g)"
        )
    }

    "CStream is invariant: a CStream[Int] does not widen to CStream[Any]" in {
        // A single declaration that only typechecks under covariant subsumption: the parameter is already typed
        // CStream[Int], so no A-inference can adapt it. Covariance would desync the static element type from the
        // stored runtime key; invariance keeps the tag honest.
        assertDoesNotCompile("def widen(s: CStream[Int]): CStream[Any] = s")
    }

end CStreamTagFreeTest
