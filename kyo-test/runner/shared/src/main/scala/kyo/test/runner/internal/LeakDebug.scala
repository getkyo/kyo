package kyo.test.runner.internal

import kyo.*

/** Leak-debug mode: opt-in attribution of a leaked descriptor to the test that opened it.
  *
  * The end-of-run leak check (see [[LeakCheck]]) reports a leaked descriptor by its `socket:[inode]` target, which on its own does not say
  * which test opened it. Snapshot diffing cannot recover that under parallel leaf execution: watching a descriptor "appear" between two
  * snapshots only says WHEN, and concurrent leaves interleave, so the opener is ambiguous. The fix is to run serially and snapshot around
  * each leaf, so a descriptor that survives a leaf is unambiguously that leaf's.
  *
  * Enabled by `KYO_TEST_LEAK_DEBUG=1` (or `-Dkyo.test.leakDebug=true`). When on it (1) forces [[LeafPool.globalK]] to 1 so leaves run one at
  * a time across the whole process, and (2) lets the JVM runner register a [[leafProbe]] that snapshots open descriptors around each leaf and
  * records `inode -> leaf` for the final report. Off by default, so a normal parallel run pays nothing.
  */
object LeakDebug:

    /** True when leak-debug mode is requested via the environment variable or system property. Read once at class init (the flag is set before
      * the JVM starts), so it is a stable `val` usable from the process-global [[LeafPool.globalK]].
      */
    val enabled: Boolean =
        sys.env.get("KYO_TEST_LEAK_DEBUG").orElse(sys.props.get("kyo.test.leakDebug"))
            .exists(v => v == "1" || v.equalsIgnoreCase("true"))

    /** Per-leaf attribution hook the JVM runner installs in leak-debug mode (Absent off-JVM, or when leak-debug is off, so the leaf path pays
      * nothing). Given a leaf path it snapshots the open descriptors and returns a finalizer that, run after the leaf body, records which
      * descriptors the leaf left open against that leaf.
      */
    @volatile var leafProbe: Maybe[Chunk[String] => (() => Unit)] = Absent

    private val noOp: () => Unit = () => ()

    /** Begin attribution for a leaf, returning the after-leaf finalizer. A no-op (and a single shared closure, no allocation) when no probe is
      * installed, so the non-debug leaf path is untouched.
      */
    def beginLeaf(path: Chunk[String]): () => Unit =
        leafProbe match
            case Maybe.Present(f) => f(path)
            case Maybe.Absent     => noOp

end LeakDebug
