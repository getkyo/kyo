package kyo.test.runner.internal

import kyo.test.internal.TestBase
import scala.scalanative.reflect.Reflect

/** Scala Native reflective instantiation for the self-contained runner.
  *
  * Targets `kyo.test.internal.TestBase`. Uses `Reflect.lookupInstantiatableClass`, which requires the class (or a supertype) to carry
  * `@EnableReflectiveInstantiation`; `kyo.test.internal.KyoTestReflect` (mixed into `TestBase`) carries it on Native, so all concrete
  * suites are reflectively instantiable. Java reflection (`getDeclaredConstructor().newInstance()`) is NOT used because
  * `java.lang.reflect.Constructor` is absent from the Scala Native javalib. The reflective `newInstance` returns `Any`; a typed pattern
  * match (not a cast) narrows it to `TestBase[?]`.
  */
private[runner] object InstantiatePlatform:

    def newInstance(suite: Class[? <: TestBase[?]]): TestBase[?] =
        val fqn = suite.getName
        val instance =
            Reflect
                .lookupInstantiatableClass(fqn)
                .getOrElse(
                    throw new ClassNotFoundException(
                        s"kyo-test: cannot reflectively instantiate '$fqn'. " +
                            "Ensure the class is a top-level (non-inner) class. " +
                            "On Scala Native, the kyo.test base carries @EnableReflectiveInstantiation via KyoTestReflect, " +
                            "which is inherited by all next suites."
                    )
                )
                .newInstance()
        instance match
            case t: TestBase[?] => t
            case other =>
                throw new ClassCastException(
                    s"kyo-test: reflectively instantiated '$fqn' but it is not a kyo.test.internal.TestBase: ${other.getClass.getName}"
                )
        end match
    end newInstance

end InstantiatePlatform
