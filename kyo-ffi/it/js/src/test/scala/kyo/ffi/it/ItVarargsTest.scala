package kyo.ffi.it

/** Variadic (`Any*`) function IT coverage, JS.
  *
  * All 4 test cases are inherited from [[ItVarargsSharedTest]]. JS has no additional varargs error-path test because koffi silently
  * discards unrecognised vararg types rather than throwing; the JVM-only `FfiLoadError.Unsupported` case therefore lives in the JVM
  * [[ItVarargsTest]].
  */
class ItVarargsTest extends ItVarargsSharedTest
