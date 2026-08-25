package kyo.kernel

import kyo.Id
import kyo.Tag
import org.scalatest.freespec.AnyFreeSpec
import scala.reflect.ClassTag

/** Pins the compiled size of the ArrowEffect expansions at user call sites, mirroring the old kernel's BytecodeTest rows for suspend,
  * suspendWith, and the region constructor.
  *
  * The numbers are measured against this kernel, not carried over from the previous one: `handle` became `handleCont` and takes a done
  * clause, so the region row is not the same expansion the old pin described. A size that moves here is a result to look at, never a
  * baseline to quietly rewrite.
  */
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
        // one allocation and a return. The old kernel pinned this at 16
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
        // 37 against the old kernel's 33 for `handle`, and the two are not the same expansion: this
        // one carries a done clause the old one did not, and it opens with the settled-input check.
        // It stood at 87 while the settled arm went through `unsafeGet`, an inline extension on the
        // opaque type, which made the expansion carry a proxy chain for the type's owner. Reading
        // the settled value through `Nested.unnest` instead halved it, and it went 44 to 37 when the
        // region stopped binding the matched body and stored the value it already had. The call site
        // still allocates twice, the region node and the handler it holds, where the old kernel's
        // region was a single node; whether the handler should fuse into the node is a design
        // question, not a pin to adjust. 37 to 40 when Effect.defer stopped being @static (see the
        // PendingBytecodeTest lift pins for the crash evidence): the expansion loads the module
        // before the call.
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
