package kyo.kernel

import kyo.*
import kyo.Tag
import scala.reflect.ClassTag

class BytecodeTest extends Test:

    object TestEffect extends ArrowEffect[Id, Id]

    class TestSuspend:
        def test() = ArrowEffect.suspend[Int](Tag[TestEffect.type], 42)

    class TestSuspendMap:
        def test() = ArrowEffect.suspendWith[Int](Tag[TestEffect.type], 42)(_ + 1)

    class TestMap:
        def test(v: Int < TestEffect.type) = v.map(_ + 1)

    class TestHandle:
        def test(v: Int < TestEffect.type) = ArrowEffect.handle(Tag[TestEffect.type], v)([C] => (input, cont) => cont(input))

    "suspend" in {
        val map = methodBytecodeSize[TestSuspend]
        assert(map == Map("test" -> 16))
    }

    "suspendWith" in {
        val map = methodBytecodeSize[TestSuspendMap]
        assert(map == Map("test" -> 16))
    }

    "map" in {
        val map = methodBytecodeSize[TestMap]
        assert(map == Map("test" -> 18, "anonfun" -> 10, "mapLoop" -> 149))
    }

    "handle" in {
        val map = methodBytecodeSize[TestHandle]
        assert(map == Map("test" -> 26, "anonfun" -> 8, "handleLoop" -> 253))
    }

    def methodBytecodeSize[A](using ct: ClassTag[A]): Map[String, Int] =
        import javassist.*
        val classpath = System.getProperty("java.class.path")
        val classPool = ClassPool.getDefault
        classpath.split(":").foreach { path =>
            classPool.insertClassPath(path)
        }
        val ctClass = classPool.get(ct.runtimeClass.getName())
        val methods = ctClass.getDeclaredMethods
        methods.map(m =>
            normalizeMethodName(m.getName()) -> m.getMethodInfo.getCodeAttribute.getCodeLength
        ).toMap.filter(!_._1.isEmpty())
    end methodBytecodeSize

    def normalizeMethodName(methodName: String): String =
        val simpleMethodName = methodName.split("\\$\\$").last
        val normalizedName   = simpleMethodName.stripPrefix("_$")
        normalizedName.split("\\$").head
    end normalizeMethodName

end BytecodeTest
