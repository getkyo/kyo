package kyo.kernel

import kyo.Id
import kyo.Tag
import org.scalatest.freespec.AnyFreeSpec
import scala.reflect.ClassTag

class ArrowEffectBytecodeTest extends AnyFreeSpec:

    object TestEffect extends ArrowEffect[Id, Id]

    class TestSuspend:
        def test() = ArrowEffect.suspend[Int](Tag[TestEffect.type], 42)

    class TestSuspendMap:
        def test() = ArrowEffect.suspendWith[Int](Tag[TestEffect.type], 42)(_ + 1)

    class TestHandleCont:
        def test(v: Int < TestEffect.type) =
            ArrowEffect.handleCont(Tag[TestEffect.type], v)([C] => (input, cont) => cont(input), a => a)

    "suspend" in {
        val sizes = methodBytecodeSize[TestSuspend]
        assert(sizes == Map("test" -> 14), sizes.toString)
    }

    "suspendWith" in {
        val sizes = methodBytecodeSize[TestSuspendMap]
        assert(sizes == Map("test" -> 14), sizes.toString)
    }

    // Region.NoEscape is a type, so the handling method carries no bytecode for it. The `done` clause expands at both of its use
    // sites (the region's hook and the settled fast path) rather than through a local method, so the caller carries the clause's
    // body inline and no nested method is emitted for it.
    "handleCont" in {
        val sizes = methodBytecodeSize[TestHandleCont]
        assert(sizes == Map("test" -> 54), sizes.toString)
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
