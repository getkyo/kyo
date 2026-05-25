package kyo.internal

import kyo.*
import kyo.Test

/** Drift-guard: every `given Schema[T]` declared in the `Schema` companion must be reachable through
  * `isSerializableType`. The check is performed by [[SerializationMacroDriftMacro.unrecognisedGivens]], which at
  * compile time enumerates `Schema.type` declared givens, peels each declared return type from `Schema[T]` to `T`, and
  * runs the gate. Any types that the gate rejects are reported as a comma-separated string so the failure message
  * names the missing entries directly.
  *
  * Companion leaves cover the three two-argument shapes the gate special-cases (`Either`, the four-arity tuple
  * ladder, `kyo.Span`) so the suite still fails loudly if the macro path silently misses one of them.
  */
class SerializationMacroDriftTest extends Test:

    "every Schema companion given is recognised by isSerializableType" in {
        val unrecognised: Seq[String] = SerializationMacroDriftMacro.unrecognisedGivens
        assert(unrecognised.isEmpty, s"isSerializableType rejected: ${unrecognised.mkString(", ")}")
    }

    "Either[String, Int] is recognised" in {
        assert(SerializationMacroDriftMacro.isRecognised[Either[String, Int]])
    }

    "Tuple3[Int, String, Boolean] is recognised" in {
        assert(SerializationMacroDriftMacro.isRecognised[(Int, String, Boolean)])
    }

    "Span[Int] is recognised" in {
        assert(SerializationMacroDriftMacro.isRecognised[kyo.Span[Int]])
    }

end SerializationMacroDriftTest
