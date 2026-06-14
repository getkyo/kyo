package kyo.ffi

/** Legacy subtype of [[FfiLoadError.AbiMismatch]]. Thrown at generated-impl static initialization when a struct's platform-measured byte
  * size does not match the size the code generator computed from the case-class layout.
  *
  * Typical cause: the user's C declaration applies `#pragma pack(1)` (or equivalent) but the corresponding Scala case class is not listed
  * in [[kyo.ffi.Ffi.Config.packedStructs]], so the generator emits a naturally-aligned layout while the C compiler emitted a packed one.
  * Silent disagreement would corrupt struct field reads/writes; the ABI self-check surfaces it at load time instead.
  *
  * The exception message names the binding trait FQN, the struct simple name, and the expected + actual byte sizes plus a remediation hint.
  * New code should catch [[FfiLoadError]] (or [[FfiLoadError.AbiMismatch]]); this type remains as a deprecated subtype so existing `catch
  * FfiAbiMismatch` blocks continue to match, and the per-struct diagnostic fields (`traitFqn`, `structName`, `expectedSize`, `actualSize`)
  * stay accessible.
  */
@deprecated("Use FfiLoadError.AbiMismatch", "0.2.0")
final class FfiAbiMismatch(
    val traitFqn: String,
    val structName: String,
    val expectedSize: Long,
    val actualSize: Long,
    msg: String
) extends FfiLoadError.AbiMismatch(expectedSize.toString, actualSize.toString):
    override def getMessage: String = msg
end FfiAbiMismatch
