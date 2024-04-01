package kyoTest

import org.scalatest.Ignore
import org.scalatest.Tag
object Tagged:
    private def ignoreIf(cond: => Boolean) = if cond then "" else classOf[Ignore].getName
    object jvmOnly extends Tag(ignoreIf(Platform.isJVM))
    object jsOnly  extends Tag(ignoreIf(Platform.isJS))
end Tagged
