package kyo.proto.kernel

import kyo.Id
import kyo.Tag
import org.scalatest.freespec.AnyFreeSpec
import scala.reflect.ClassTag

class ArrowEffectBytecodeTest extends AnyFreeSpec:

    object TestEffect extends ArrowEffect[Id, Id]

    class TestSuspend:
        def test() = ArrowEffect.suspend[Int](Tag[TestEffect.type], 42)

    class TestSuspendWith:
        def test() = ArrowEffect.suspendWith[Int](Tag[TestEffect.type], 42)(_ + 1)

    class TestHandleCont:
        def test(v: Int < TestEffect.type) =
            ArrowEffect.handleCont(Tag[TestEffect.type], v)([C] => (input, cont) => cont(input), a => a)

    "suspend" in {
        val sizes = methodBytecodeSize[TestSuspend]
        assert(sizes == Map("test" -> 14), sizes.toString)
    }

    "suspendWith" in {
        val sizes = methodBytecodeSize[TestSuspendWith]
        assert(sizes == Map("test" -> 14), sizes.toString)
    }

    "handleCont" in {
        val sizes = methodBytecodeSize[TestHandleCont]
        assert(sizes == Map("test" -> 48), sizes.toString)
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

end ArrowEffectBytecodeTest
