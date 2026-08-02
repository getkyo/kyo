package kyo

import org.scalatest.Tag

/** ScalaTest tag marking test leaves whose container cannot be shared with the per-fork-JVM shared-container singleton that
  * `kyo-sql-tests` holds. Each such leaf starts and owns its own container, so the tag routes nothing: the build carries no `testGrouping`
  * for these modules. It is documentation, plus a handle for leaf-level filtering via
  * `sbt 'kyo-sql-testsJVM/testOnly -- -n kyo.OwnContainer'`.
  *
  * It stays in core so all three modules see it through their `test->test` edge, which is also why it names no engine type.
  */
object OwnContainer extends Tag("kyo.OwnContainer")
