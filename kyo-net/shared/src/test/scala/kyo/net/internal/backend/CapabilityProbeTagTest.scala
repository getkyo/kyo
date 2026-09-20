package kyo.net.internal.backend

import kyo.net.Test

/** The `<os>-<arch>` tag a demotion names, asserted on every platform because the tag is derived from platform
  * APIs and the platforms differ in which ones answer.
  *
  * Kept apart from CapabilityProbeTest, which is jvm-native for the class-initializer shapes it feeds the
  * classifier: reading os.arch through `sys.props` answers on the JVM and Native and not on JS or Wasm, so a
  * jvm-native leaf reports green for a tag that reads "<os>-unknown" on half the platforms and sends a reader
  * after a classifier artifact published under no such name.
  */
class CapabilityProbeTagTest extends Test:

    "names this runtime's os and architecture" in {
        val tag = CapabilityProbe.platform
        assert(tag.contains("-"), s"expected an <os>-<arch> tag, got $tag")
        // The halves are asserted separately: a tag is useless with either one unresolved, and "unknown-x86_64"
        // and "darwin-unknown" both satisfy a shape check.
        assert(!tag.startsWith("unknown-"), s"the os half did not resolve: $tag")
        assert(!tag.endsWith("-unknown"), s"the arch half did not resolve: $tag")
    }

end CapabilityProbeTagTest
