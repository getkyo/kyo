package kyo

import org.scalatest.Tag

/** ScalaTest tag marking test leaves whose container cannot be shared with the per-fork-JVM SqlSharedContainers singleton. Routing is
  * class-level (see build.sbt's testGrouping); the tag is for documentation + leaf-level filtering via
  * `sbt 'kyo-sql/testOnly -- -n kyo.OwnContainer'`.
  */
object OwnContainer extends Tag("kyo.OwnContainer")
