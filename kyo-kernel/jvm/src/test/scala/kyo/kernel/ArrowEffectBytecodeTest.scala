package kyo.kernel

import kyo.Id
import kyo.Tag
import scala.reflect.ClassTag

/** Pins the compiled size of the ArrowEffect expansions at user call sites: suspend, suspendWith, and the region constructor.
  *
  * A size that moves here is a result to look at, never a baseline to quietly rewrite.
  */
class ArrowEffectBytecodeTest extends kyo.test.Test[Any]:

    object TestEffect extends ArrowEffect[Id, Id]

    class TestSuspend:
        def test() = ArrowEffect.suspend[Int](Tag[TestEffect.type], 42)

    class TestSuspendWith:
        def test() = ArrowEffect.suspendWith[Int](Tag[TestEffect.type], 42)(_ + 1)

    class TestHandleCont:
        def test(v: Int < TestEffect.type) =
            ArrowEffect.handleCont(Tag[TestEffect.type], v)([C] => (input, cont) => cont(input), a => a)

    "suspend" in {
        // one allocation and a return
        val sizes = methodBytecodeSize[TestSuspend]
        assert(sizes == Map("test" -> 14))
    }

    "suspendWith" in {
        // the suspension and its continuation fuse into one anonymous class, so the call site is a
        // single allocation, the same size as a bare suspend
        val sizes = methodBytecodeSize[TestSuspendWith]
        assert(sizes == Map("test" -> 14))
    }

    "handleCont" in {
        // the expansion opens with the settled-input check (reading the settled value through
        // `Nested.unnest`; an inline extension on the opaque type would drag a proxy chain for the
        // type's owner into every site), carries the done clause, and allocates twice: the region
        // node and the handler it holds. Whether the handler should fuse into the node is a design
        // question, not a pin to adjust. The expansion also loads the Effect module before the
        // deferral call: an @static symbol named in inline-expanded code crashes a downstream
        // macro-owning module's clean build (see the PendingBytecodeTest lift pins).
        val sizes = methodBytecodeSize[TestHandleCont]
        assert(sizes == Map("test" -> 40))
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
