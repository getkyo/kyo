package kyoTest

import kyo.*
import scala.reflect.ClassTag

class coreBytecodeSizeTest extends KyoTest:

    import kyo.core.*

    object TestEffect extends Effect[TestEffect.type]:
        type Command[T] = T

    object TestHandler extends Handler[Id, TestEffect.type, Any]:
        def resume[T, U: Flat, S](command: T, k: T => U < (TestEffect.type & S))(using Tag[TestEffect.type]) =
            Resume((), k(command))

    class TestSuspend:
        def test(e: TestEffect.type) = suspend(e)(42)

    class TestTransform:
        def test(v: Int < TestEffect.type) = transform(v)(_ + 1)

    class TestHandle:
        def test(h: TestHandler.type, v: Int < TestEffect.type) = TestEffect.handle(h)((), v)

    "suspend" in runJVM {
        val map = methodBytecodeSize[TestSuspend]
        assert(map == Map("test" -> 14))
    }

    "transform" in runJVM {
        val map = methodBytecodeSize[TestTransform]
        assert(map == Map("test" -> 16, "anonfun" -> 6, "transformLoop" -> 42))
    }

    "handle" in runJVM {
        val map = methodBytecodeSize[TestHandle]
        assert(map == Map(
            "test"        -> 27,
            "resultLoop"  -> 91,
            "handleLoop"  -> 265,
            "_handleLoop" -> 10
        ))
    }

    def methodBytecodeSize[T](using ct: ClassTag[T]): Map[String, Int] =
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
end coreBytecodeSizeTest
