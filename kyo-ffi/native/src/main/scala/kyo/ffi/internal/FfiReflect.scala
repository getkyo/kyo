package kyo.ffi.internal

import scala.scalanative.reflect.Reflect

/** Scala Native implementation of the cross-platform reflective instantiation hook used by `Ffi.load[T]`.
  *
  * Uses `scala.scalanative.reflect.Reflect.lookupInstantiatableClass`, Scala Native's linker-aware replacement for the JVM's
  * `Class.forName(...).newInstance()` (the JVM form is not implemented in the Native javalib). Requires the target class to be annotated
  * with `@scala.scalanative.reflect.annotation.EnableReflectiveInstantiation` (the codegen's NativeEmitter applies this to every generated
  * `{T}Impl`) so the linker retains the class's nullary constructor.
  *
  * Delegates the `Option`-unwrap / error-throw flow to the shared [[FfiReflectCore]].
  */
object FfiReflect:

    /** Load class `implName` and invoke its nullary constructor.
      *
      * @throws IllegalStateException
      *   if the class is not found or lacks `@EnableReflectiveInstantiation`, the typical cause is that the kyo-ffi code generator did not
      *   run, its output is not on the runtime classpath, or (on Scala Native linker) the annotation is missing.
      */
    def instantiate(implName: String, traitFqn: String): AnyRef =
        FfiReflectCore.instantiate(
            implName,
            traitFqn,
            name => Reflect.lookupInstantiatableClass(name).map(c => () => c.newInstance().asInstanceOf[AnyRef]),
            FfiPlatformErrors.implClassNotFoundNative
        )
end FfiReflect
