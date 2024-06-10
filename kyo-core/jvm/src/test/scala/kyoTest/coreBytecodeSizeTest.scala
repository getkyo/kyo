package kyoTest

import kyo.*
import scala.reflect.ClassTag

class coreBytecodeSizeTest extends KyoTest:

    import kyo.core.*

    sealed trait TestEffect extends Effect[Const[Int], Const[Int]]

    class TestSuspend:
        def test = suspend[Any](Tag[TestEffect], 42)

    class TestTransform:
        def test(v: Int < TestEffect) = bind(v)(_ + 1)

    class TestHandle:
        def test(v: Int < TestEffect): Int < Any =
            handle[Const[Int], Const[Int], TestEffect, Int, Any, Any](
                Tag[TestEffect],
                v
            )([C] => (input, cont) => cont(input + 1))
    end TestHandle

    "suspend" in runJVM {
        val map = methodBytecodeSize[TestSuspend]
        assert(map == Map("test" -> 40))
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
            "handleLoop"  -> 292,
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
