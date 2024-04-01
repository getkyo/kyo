package kyoTest

import org.scalatest.Ignore
import org.scalatest.Tag

object Tagged:
    val jvmOnly = Tag(if Platform.isJVM then "" else classOf[Ignore].getName)
    val jsOnly  = Tag(if Platform.isJS then "" else classOf[Ignore].getName)
