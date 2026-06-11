package kyo.ffi.codegen.model

/** A case class used as a struct parameter, nested struct, or multi-value return.
  *
  * @param fqcn
  *   fully-qualified case class name, e.g. `"kyo.example.ConnectResult"`.
  * @param simpleName
  *   unqualified case class name, e.g. `"ConnectResult"`.
  * @param fields
  *   fields in declaration order.
  * @param packed
  *   true when the user listed this struct in `Ffi.Config.packedStructs`, emitters produce packed-layout code.
  */
final case class StructSpec(
    fqcn: String,
    simpleName: String,
    fields: List[StructField],
    packed: Boolean
):
    /** Int/Long sibling candidates used as the size for a `Buffer[A]` field in this struct.
      *
      * Mirrors [[kyo.ffi.codegen.FfiInspector]]'s top-level `Borrowed[Buffer[A]]` rule: infer the buffer extent from exactly one Int/Long
      * sibling field. Zero or multiple candidates make the inference ambiguous; the validator rejects such structs via
      * [[kyo.ffi.internal.FfiGenErrors]].
      */
    def intLongSizeCandidates: List[StructField] =
        fields.filter(f => f.tpe == TypeRef.IntT || f.tpe == TypeRef.LongT)

    /** The sole Int/Long sibling resolved as the size for any `Buffer[A]` field in this struct, or `None` when absent/ambiguous. */
    def inferredBufferSizeField: Option[StructField] =
        intLongSizeCandidates match
            case single :: Nil => Some(single)
            case _             => None

    /** True iff this struct has at least one `Buffer[A]` field. */
    def hasBufferField: Boolean =
        fields.exists(f => f.tpe.isInstanceOf[TypeRef.BufferT])
end StructSpec

/** One field in a [[StructSpec]]. */
final case class StructField(name: String, tpe: TypeRef)
