package kyo.test.internal

/** Marker trait for cross-platform reflective instantiation support (JVM variant).
  *
  * Extended by [[kyo.test.internal.TestBase]] (and therefore inherited by every concrete suite and every internal fixture). On JS and
  * Native the platform copies of this file carry `@EnableReflectiveInstantiation` so concrete subclasses are reachable through
  * `scala.scalajs.reflect.Reflect` / `scala.scalanative.reflect.Reflect`. JVM uses plain `getDeclaredConstructor()` and needs no
  * annotation. Distinct from [[kyo.test.SuiteFingerprintMarker]] which marks classes for sbt test discovery.
  */
private[kyo] trait KyoTestReflect
