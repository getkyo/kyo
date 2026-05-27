package kyo.fixtures

// JVM-only: a plain class with no Scala-specific features, used as a known TASTy baseline.
// This produces a straightforward TASTy and classfile pair that tests can load from the
// JVM classpath by name to verify classfile reader round-trips.
class JvmPlainClass(val jvmField: String):
    def jvmMethod(n: Int): Int = n * 2
end JvmPlainClass
