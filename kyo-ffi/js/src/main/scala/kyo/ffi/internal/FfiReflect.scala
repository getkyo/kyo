package kyo.ffi.internal

import kyo.ffi.FfiUnsupported
import scala.scalajs.reflect.Reflect

/** Scala.js implementation of the cross-platform reflective instantiation hook used by `Ffi.load[T]`.
  *
  * Uses `scala.scalajs.reflect.Reflect.lookupInstantiatableClass`, Scala.js's linker-aware replacement for the JVM's
  * `Class.forName(...).newInstance()`. Requires the target class to be annotated with
  * `@scala.scalajs.reflect.annotation.EnableReflectiveInstantiation` (the codegen's JsEmitter applies this to every generated `{T}Impl`) so
  * the linker retains the class's nullary constructor.
  *
  * Before any reflective lookup, this implementation consults [[NativeLoader.detectBrowser]] and throws [[FfiUnsupported]] if the current
  * runtime is a browser, the generated impl would immediately fail at static-init time trying to load koffi, but throwing here yields a
  * more informative error from the `Ffi.load` call site.
  *
  * Delegates the `Option`-unwrap / error-throw flow to the shared [[FfiReflectCore]].
  */
object FfiReflect:

    /** Load class `implName` and invoke its nullary constructor.
      *
      * @throws FfiUnsupported
      *   if the current runtime is a browser (neither `process` nor `require` is defined).
      * @throws IllegalStateException
      *   if the class is not found or lacks `@EnableReflectiveInstantiation`, the typical cause is that the kyo-ffi code generator did not
      *   run, its output is not on the runtime classpath, or (on Scala.js linker) the annotation is missing.
      */
    def instantiate(implName: String, traitFqn: String): AnyRef =
        if NativeLoader.detectBrowser() then
            throw new FfiUnsupported(FfiPlatformErrors.BrowserUnsupportedReflect)
        end if
        FfiReflectCore.instantiate(
            implName,
            traitFqn,
            name => Reflect.lookupInstantiatableClass(name).map(c => () => c.newInstance().asInstanceOf[AnyRef]),
            FfiPlatformErrors.implClassNotFoundJs
        )
    end instantiate
end FfiReflect
