// package kyo.kernel
//
// import kyo.Id
// import kyo.Tag
// import org.scalatest.freespec.AnyFreeSpec
// import scala.reflect.ClassTag
//
// /** Pins the compiled size of the ArrowEffect expansions at user call sites, mirroring the
//   * old kernel's BytecodeTest rows for suspend, suspendWith, and handle.
//   */
// class ArrowEffectBytecodeTest extends AnyFreeSpec:
//
//     object TestEffect extends ArrowEffect[Id, Id]
//
//     class TestSuspend:
//         def test() = ArrowEffect.suspend[Int](Tag[TestEffect.type], 42)
//
//     class TestSuspendWith:
//         def test() = ArrowEffect.suspendWith[Int](Tag[TestEffect.type], 42)(_ + 1)
//
//     class TestHandle:
//         def test(v: Int < TestEffect.type) = ArrowEffect.handle(Tag[TestEffect.type], v)([C] => (input, cont) => cont(input))
//
//     "suspend" in {
//         val sizes = methodBytecodeSize[TestSuspend]
//         assert(sizes == Map("test" -> 16))
//     }
//
//     "suspendWith" in {
//         val sizes = methodBytecodeSize[TestSuspendWith]
//         // the suspension and its continuation fuse into one anonymous class, so
//         // the call site is a single allocation, the same size as a bare suspend
//         assert(sizes == Map("test" -> 16))
//     }
//
//     "handle" in {
//         val sizes = methodBytecodeSize[TestHandle]
//         // the deep region is one node that stays installed, so the call site is a
//         // single allocation with the settled check and no lifted loop method
//         assert(sizes == Map("test" -> 33))
//     }
//
//     private def methodBytecodeSize[A](using ct: ClassTag[A]): Map[String, Int] =
//         import javassist.*
//         val classpath = java.lang.System.getProperty("java.class.path")
//         val classPool = ClassPool.getDefault
//         classpath.split(java.io.File.pathSeparator).foreach { path =>
//             classPool.insertClassPath(path)
//         }
//         val ctClass = classPool.get(ct.runtimeClass.getName())
//         val methods = ctClass.getDeclaredMethods
//         methods.map(m =>
//             normalizeMethodName(m.getName()) -> m.getMethodInfo.getCodeAttribute.getCodeLength
//         ).toMap.filter(!_._1.isEmpty())
//     end methodBytecodeSize
//
//     private def normalizeMethodName(methodName: String): String =
//         val simpleMethodName = methodName.split("\\$\\$").last
//         val normalizedName   = simpleMethodName.stripPrefix("_$")
//         normalizedName.split("\\$").head
//     end normalizeMethodName
//
// end ArrowEffectBytecodeTest
