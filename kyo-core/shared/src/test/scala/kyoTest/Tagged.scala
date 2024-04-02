package kyoTest

import org.scalatest.Tag

object Tagged:
    private def runWhen(cond: => Boolean) = if cond then "" else "org.scalatest.Ignore"
    object jvmOnly extends Tag(runWhen(Platform.isJVM))
    object jsOnly  extends Tag(runWhen(Platform.isJS))
end Tagged
