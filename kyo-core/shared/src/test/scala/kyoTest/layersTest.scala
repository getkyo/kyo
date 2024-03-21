package kyoTest

import kyo.*
import scala.util.Failure

class LayersTest extends KyoTest:
    case class Dep1(int: Int)
    case class Dep2(str: String)
    case class Dep3(bool: Boolean)
    case class Dep(dep1: Int, dep2: String, dep3: Boolean)

    val depLayer  = Envs[Dep].layer(Dep(1, "hello", true))
    val dep1Layer = Envs[Dep1].layer(Envs[Dep].get.map(v => Dep1(v.dep1)))
    val dep2Layer = Envs[Dep2].layer(Envs[Dep].get.map(v => Dep2(v.dep2)))
    val dep3Layer = Envs[Dep3].layer(Envs[Dep].get.map(v => Dep3(v.dep3)))

    "Envs layers should be composable and provide multiple dependencies" in {
        val layer = dep1Layer.andThen(dep2Layer).andThen(dep3Layer).chain(depLayer)
        val effect =
            for
                dep1 <- Envs[Dep1].get
                dep2 <- Envs[Dep2].get
                dep3 <- Envs[Dep3].get
            yield s"${dep1.int}-${dep2.str}-${dep3.bool}"
        val handledEffect = layer.run(effect)
        assert(handledEffect == "1-hello-true")
    }

    case class TestError1(msg: String)
    case class TestError2(msg: String)

    val stringToTE1Layer = Aborts[String].layer(str => Aborts[TestError1].fail(TestError1(str)))
    val dep1ToTE1Layer   = Aborts[Dep2].layer(dep => Aborts[TestError1].fail(TestError1(dep.str)))
    val throwableToTE1Layer =
        Aborts[Throwable].layer(err => Aborts[TestError1].fail(TestError1(err.getMessage())))
    val testError1ToTE2Layer =
        Aborts[TestError1].layer(err => Aborts[TestError2].fail(TestError2(err.msg)))

    "Aborts layers should handle string failures" in {
        val layer  = stringToTE1Layer.chain(testError1ToTE2Layer)
        val effect = Aborts[String].fail("string failure")
        assert(Aborts[TestError2].run(layer.run(effect)) == Left(TestError2("string failure")))
    }

    "Aborts layers should handle Dep2 failures" in {
        val layer  = dep1ToTE1Layer.chain(testError1ToTE2Layer)
        val effect = Aborts[Dep2].fail(Dep2("dep2 failure"))
        assert(Aborts[TestError2].run(layer.run(effect)) == Left(TestError2("dep2 failure")))
    }

    "Aborts layers should handle Throwable failures" in {
        val layer  = throwableToTE1Layer.chain(testError1ToTE2Layer)
        val effect = Aborts[Throwable].fail(new Exception("throwable failure"))
        assert(Aborts[TestError2].run(layer.run(effect)) == Left(TestError2("throwable failure")))
    }

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
