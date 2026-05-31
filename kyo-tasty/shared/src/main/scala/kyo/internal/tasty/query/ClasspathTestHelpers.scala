package kyo.internal.tasty.query

import kyo.AllowUnsafe
import kyo.Tasty

/** Internal test helpers for classpath-related test setup.
  *
  * plan: phase-02 bridge; the assignHomesForTest / assignExtraHomes methods operated on Symbol.home (ClasspathRef slots) which are removed
  * in Phase 02. These methods are now no-ops since Symbol no longer carries a ClasspathRef. The stubs remain for source compatibility until
  * Phase 07 removes them along with ClasspathRef.
  */
object ClasspathTestHelpers:

    /** No-op stub. Symbol.home is removed in Phase 02; home assignment no longer required. */
    private[kyo] def assignHomesForTest(cp: Classpath): Unit = ()

    /** No-op stub. Symbol.home is removed in Phase 02; home assignment no longer required. */
    private[kyo] def assignExtraHomes(cp: Tasty.Classpath, extra: Seq[Tasty.Symbol]): Unit = ()

end ClasspathTestHelpers
