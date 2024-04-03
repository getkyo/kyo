package kyoTest

import kyo.*
import scala.util.Failure

class LayersTest extends KyoTest:
    case class Dep1(int: Int)
    case class Dep2(str: String)
    case class Dep3(bool: Boolean)
    case class Dep(dep1: Int, dep2: String, dep3: Boolean)

    case class TestError1(msg: String)
    case class TestError2(msg: String)

    val optionsToAbortsLayer = Options.layer(Aborts[String].fail("missing value"))
    val optionsToTriesLayer  = Options.layer(IOs.fail("missing value"))

    "Options layer should handle None as Aborts failure" in {
        val effect                = Options.get(None)
        val effectHandledToAborts = optionsToAbortsLayer.run(effect)
        assert {
            Aborts[String].run(effectHandledToAborts) match
                case Left("missing value") => true
                case _                     => false
        }
    }

    "Options layer should handle None as IOs failure" in {
        val effect               = Options.get(None)
        val effectHandledToTries = optionsToTriesLayer.run(effect)
        assert {
            IOs.run(IOs.attempt(effectHandledToTries)) match
                case Failure(err: Throwable) => err.getMessage == "missing value"
                case _                       => false
        }
    }
end LayersTest
