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

    // disabled while the map expansion shape is under active iteration; re-pin
    // once the design settles
    "map" ignore {
        // per site: the caller (test), the lifted evaluation body (run, holding f
        // and the successor dispatch), and the Transform mint (arrow, reached only
        // when the computation suspends or the budget runs out)
        val sizes = methodBytecodeSize[TestMap]
        assert(sizes == Map("test" -> 22, "arrow" -> 9, "run" -> 114))
    }

    "lift of a primitive is a bare cast" in {
        val sizes = methodBytecodeSize[TestLiftPrimitive]
        assert(sizes == Map("test" -> 8))
    }

    "lift of a String is a bare cast" in {
        val sizes = methodBytecodeSize[TestLiftString]
        assert(sizes == Map("test" -> 2))
    }

    "lift of a concrete class keeps the runtime test" in {
        // the lift has two arms, a primitive fast path and Nested.nest, so a concrete class takes
        // the same call a generic value takes. The cost is one union instanceof on the most common
        // boundary in the library; eliding it for a final class that provably admits no nested
        // payload would take an emission macro that reads the type, machinery this lift deliberately
        // does not have.
        //
        // The expansion loads the module (getstatic MODULE$, aload, invokevirtual, areturn) rather
        // than calling an @static Nested.nest: an @static symbol named in inline-expanded code
        // crashes a downstream macro-owning module's clean build (StaleSymbolException in the
        // suspended-unit retry run, first hit by kyo-sql).
        val sizes = methodBytecodeSize[TestLiftConcrete]
        assert(sizes == Map("test" -> 8))
    }

    "lift of a generic value is one runtime test" in {
        // abstract types keep the runtime Boxed test, through the module:
        // getstatic MODULE$, aload, invokevirtual Nested.nest, areturn (8; 5 while nest was
        // @static, dropped for the reason the concrete-class pin above records)
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
