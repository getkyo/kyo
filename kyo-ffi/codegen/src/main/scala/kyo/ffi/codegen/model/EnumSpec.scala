package kyo.ffi.codegen.model

/** Description of a Scala 3 enum structurally detected as a C int enum during TASTy extraction.
  *
  * @param fqcn
  *   fully-qualified enum name, e.g. `"kyo.ffi.it.ItColor"`.
  * @param simpleName
  *   unqualified enum name, e.g. `"ItColor"`.
  */
final case class EnumSpec(fqcn: String, simpleName: String)
