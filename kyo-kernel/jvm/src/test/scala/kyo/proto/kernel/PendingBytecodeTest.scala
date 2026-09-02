package kyo.proto.kernel

import kyo.Id
import kyo.Tag
import org.scalatest.freespec.AnyFreeSpec
import scala.reflect.ClassTag

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
        assert(sizes == Map("test" -> 18, "run" -> 95), sizes.toString)
    }

    "lift of a primitive is a bare cast" in {
        val sizes = methodBytecodeSize[TestLiftPrimitive]
        assert(sizes == Map("test" -> 8), sizes.toString)
    }

    "lift of a String is a bare cast" in {
        val sizes = methodBytecodeSize[TestLiftString]
        assert(sizes == Map("test" -> 2), sizes.toString)
    }

    "lift of a concrete class keeps the runtime test" in {
        val sizes = methodBytecodeSize[TestLiftConcrete]
        assert(sizes == Map("test" -> 8), sizes.toString)
    }

    "lift of a generic value is one runtime test" in {
        val sizes = methodBytecodeSize[TestLiftGeneric]
        assert(sizes == Map("test" -> 8), sizes.toString)
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
