package kyo.fixtures

/** Varargs fixture for cross-platform testing of Type.Repeated (F-A2-013).
  *
  * Provides a method with a repeated parameter (`String*`) so that the embedded-fixture classpath on JS and Native exercises the same
  * Type.Repeated decoding path that the JVM standard classpath tests exercise via scala.List$.apply.
  *
  * Without this fixture the embedded set contains no repeated parameters and VarargsFidelity2Test leaf 3 would fail with count=0 on
  * JS/Native.
  */
class VarargFixture:
    /** Concatenate strings with an optional separator. xs is a varargs parameter. */
    def concat(xs: String*): String = xs.mkString(", ")
end VarargFixture
