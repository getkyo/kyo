package kyo.kernel

import kyo.Const
import kyo.Id
import kyo.Tag
import org.scalatest.freespec.AnyFreeSpec
import scala.reflect.ClassTag

/** Pins the compiled size of the Pending extension expansions: the per-site inline map cost
  * and the emission of the pure-value lift at each static shape. A size change here is a
  * change to what every user call site compiles to and must be deliberate.
  */
class PendingBytecodeTest extends AnyFreeSpec:

    object TestEffect extends ArrowEffect[Id, Id]

    class TestMap:
        def test(v: Int < TestEffect.type) = v.map(_ + 1)

    class TestLiftPrimitive:
        def test(v: Int): Int < Any = v

    class TestLiftString:
        def test(v: String): String < Any = v

    final case class Box(value: Int)

    class TestLiftConcrete:
        def test(v: Box): Box < Any = v

    class TestLiftGeneric:
        def test[A](v: A): A < Any = v

    "map" in {
        val sizes = methodBytecodeSize[TestMap]
        assert(sizes == Map("test" -> 22, "arrow" -> 10, "mapLoop" -> 113))
    }

    "lift of a primitive is a bare cast" in {
        val sizes = methodBytecodeSize[TestLiftPrimitive]
        assert(sizes == Map("test" -> 8))
    }

    "lift of a String is a bare cast" in {
        val sizes = methodBytecodeSize[TestLiftString]
        assert(sizes == Map("test" -> 2))
    }

    "lift of a concrete class is a bare cast" in {
        // a final class admits no Boxed subtype, so the lift macro proves the
        // value can never be a computation and emits a bare cast
        val sizes = methodBytecodeSize[TestLiftConcrete]
        assert(sizes == Map("test" -> 2))
    }

    "lift of a generic value is one runtime test" in {
        // abstract types keep the runtime Boxed test, as a single static call
        val sizes = methodBytecodeSize[TestLiftGeneric]
        assert(sizes == Map("test" -> 8))
    }

    private def methodBytecodeSize[A](using ct: ClassTag[A]): Map[String, Int] =
        import javassist.*
        val classpath = java.lang.System.getProperty("java.class.path")
        val classPool = ClassPool.getDefault
        classpath.split(java.io.File.pathSeparator).foreach { path =>
            classPool.insertClassPath(path)
        }
        val ctClass = classPool.get(ct.runtimeClass.getName())
        val methods = ctClass.getDeclaredMethods
        methods.map(m =>
            normalizeMethodName(m.getName()) -> m.getMethodInfo.getCodeAttribute.getCodeLength
        ).toMap.filter(!_._1.isEmpty())
    end methodBytecodeSize

    private def normalizeMethodName(methodName: String): String =
        val simpleMethodName = methodName.split("\\$\\$").last
        val normalizedName   = simpleMethodName.stripPrefix("_$")
        normalizedName.split("\\$").head
    end normalizeMethodName

end PendingBytecodeTest
