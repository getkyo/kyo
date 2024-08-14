package kyo

import org.scalatest.Tag

object Tagged:
    private def runWhen(cond: => Boolean) = if cond then "" else "org.scalatest.Ignore"
    object jvmOnly extends Tag(runWhen(kyo.kernel.Platform.isJVM))
    object jsOnly  extends Tag(runWhen(kyo.kernel.Platform.isJS))
end Tagged
