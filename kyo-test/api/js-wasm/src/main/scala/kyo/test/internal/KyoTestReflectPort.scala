package kyo.test.internal

/** Marker trait that enables reflective instantiation of suite subclasses on Scala.js.
  *
  * On Scala.js, the linker uses `@EnableReflectiveInstantiation` to include classes in the reflection registry that powers
  * `scala.scalajs.reflect.Reflect.lookupInstantiatableClass`. The annotation is inherited: annotating this trait causes every concrete
  * suite (which extends [[kyo.test.internal.TestBase]], which extends this trait) to be reflectively instantiable without any per-class
  * boilerplate.
  */
@scala.scalajs.reflect.annotation.EnableReflectiveInstantiation
private[kyo] trait KyoTestReflect
