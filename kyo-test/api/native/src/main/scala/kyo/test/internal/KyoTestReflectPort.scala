package kyo.test.internal

/** Marker trait for cross-platform reflective instantiation support (Scala Native variant).
  *
  * On Scala Native, reflection works via `getDeclaredConstructor().newInstance()` for classes annotated with
  * `@EnableReflectiveInstantiation`. The annotation on the Native version mirrors the JS version.
  */
@scala.scalanative.reflect.annotation.EnableReflectiveInstantiation
private[kyo] trait KyoTestReflect
