package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2

/** Decoder fidelity against the embedded-fixture warning sink: confirms zero unknown-tag warnings on a clean load, on the per-file path, and
  * via the production decoder. The "no unknown-tag warnings" invariant is decoder-wide and holds on any classpath; embedded fixtures
  * exercise every TASTy tag used in the fixture corpus.
  */
class RealClasspathFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    "no unknown-tag warnings on clean classpath load" in {
        TestClasspaths2.loadEmbeddedWithSink.map: (cp, sink) =>
            val unknownTagCount = sink.unknownTagCount
            assert(
                unknownTagCount == 0,
                s"Expected 0 unknown-tag warnings after term-tag routing fix (baseline 78,501 on real stdlib), found $unknownTagCount. " +
                    s"Sample: ${sink.messages.filter(m => m.contains("TASTy") || m.contains("unhandled")).take(3).mkString("; ")}"
            )
            succeed
    }

    "single-tasty-load emits zero unknown-tag warnings" in {
        TestClasspaths2.loadEmbeddedWithSink.map: (_, sink) =>
            assert(
                sink.unknownTagCount == 0,
                s"Expected 0 unknown-tag warnings on clean load, found ${sink.unknownTagCount}"
            )
            succeed
    }

    "production decoder contributes zero unknown-tag warnings" in {
        TestClasspaths2.loadEmbeddedWithSink.map: (cp, sink) =>
            assert(
                sink.unknownTagCount == 0,
                s"Expected 0 unknown-tag warnings on clean load, found ${sink.unknownTagCount}"
            )
            succeed
    }

    "embedded-fixture classpath produces non-zero symbol count" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            assert(
                cp.symbols.size > 0,
                s"Expected > 0 symbols from embedded-fixture classpath; got ${cp.symbols.size}"
            )
            succeed
    }

end RealClasspathFidelity2Test
