package kyo.fixtures

/** Varargs fixture for cross-platform testing of Type.Repeated.
  *
  * Provides a method with a repeated parameter (`String*`) so that the embedded-fixture classpath on JS and Native exercises the same
  * Type.Repeated decoding path that the JVM standard classpath tests exercise via scala.List$.apply.
  */
class VarargFixture:
    /** Concatenate strings with an optional separator. xs is a varargs parameter. */
    def concat(xs: String*): String = xs.mkString(", ")
end VarargFixture
