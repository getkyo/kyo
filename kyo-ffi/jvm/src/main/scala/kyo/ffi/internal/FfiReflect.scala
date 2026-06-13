package kyo.ffi.internal

/** JVM implementation of the cross-platform reflective instantiation hook used by `Ffi.load[T]`.
  *
  * Uses standard Java reflection, `Class.forName` + nullary `getDeclaredConstructor().newInstance()`. This is the canonical JVM approach
  * and does not require any annotation on the generated impl class; the annotation-based path is only needed on Scala.js and Scala Native
  * whose linkers otherwise erase unreferenced constructors.
  *
  * Delegates the `Option`-unwrap / error-throw flow to the shared [[FfiReflectCore]].
  */
object FfiReflect:

    /** Load class `implName` and invoke its nullary constructor.
      *
      * @throws java.lang.IllegalStateException
      *   if the class is not found or lacks a public nullary constructor, the typical cause is that the kyo-ffi code generator did not run
      *   or its output is not on the runtime classpath.
      */
    def instantiate(implName: String, traitFqn: String): AnyRef =
        FfiReflectCore.instantiate(
            implName,
            traitFqn,
            name =>
                try
                    val clazz = Class.forName(name)
                    val ctor  = clazz.getDeclaredConstructor()
                    Some(() => ctor.newInstance().asInstanceOf[AnyRef])
                catch
                    case _: ClassNotFoundException => None
                    case _: NoSuchMethodException =>
                        throw new IllegalStateException(FfiPlatformErrors.implMissingNullaryCtorJvm(name, traitFqn))
            ,
            FfiPlatformErrors.implClassNotFoundJvm
        )
    end instantiate
end FfiReflect
