package kyo.ffi.codegen

import kyo.ffi.codegen.model.*

/** Post-extraction structural validator for [[kyo.ffi.codegen.model.TraitSpec]]s.
  *
  * The bulk of rejection happens during extraction (unsupported types, single-field case-class return, recursive structs). This object adds
  * a last-line-of-defence pass: re-verifying invariants that emitters rely on.
  *
  * Returns a flat list of human-readable error strings; an empty list means the spec is ready for emission. Errors are sorted by severity
  * (critical/structural first, syntactic last) with a stable secondary order on `(memberName, message)` so the output is deterministic
  * regardless of TASTy traversal order, see findings #15/#40.
  */
object TypeValidator:

    /** Severity of a validator finding. Critical = structural breakage that would corrupt downstream emission (Guard in forbidden
      * positions, struct cycles). Structural = method-shape violations (callback/Guard parity, multi-value field-count). Syntactic =
      * use-site type problems (unsupported parameter types, unknown struct references).
      *
      * Errors are emitted in this order so the user sees the most-actionable finding first.
      */
    enum Severity derives CanEqual:
        case Critical, Structural, Syntactic

    final private case class Entry(severity: Severity, member: String, message: String)

    /** Validate the given trait. Empty list = OK, non-empty = errors sorted by `(severity, member, message)`. */
    def validate(spec: TraitSpec): List[String] =
        val structsByName = spec.structs.map(s => s.fqcn -> s).toMap
        val enumsByFqcn   = spec.enums.map(e => e.fqcn -> e).toMap

        val entries = List.newBuilder[Entry]

        // Every method's param types must be supported, no GuardT in forbidden positions.
        for m <- spec.methods do
            val loc    = s"${spec.simpleName}.${m.scalaName}"
            val member = s"${spec.fqcn}.${m.scalaName}"
            // Guard may only appear as a parameter, never a field/return. (Critical, emitter cannot synthesize a Guard return.)
            m.returnShape match
                case ReturnShape.Primitive(TypeRef.GuardT) =>
                    entries += Entry(Severity.Critical, member, s"$loc: Ffi.Guard is not a valid return type")
                case _ => ()
            end match

            // All referenced struct types must be present in the accum.
            m.params.foreach { p =>
                p.tpe match
                    case TypeRef.StructT(n) if !structsByName.contains(n) =>
                        entries += Entry(
                            Severity.Syntactic,
                            member,
                            s"$loc: parameter '${p.name}' references unknown struct '$n'"
                        )
                    case TypeRef.EnumT(n) if !enumsByFqcn.contains(n) =>
                        entries += Entry(
                            Severity.Syntactic,
                            member,
                            s"$loc: parameter '${p.name}' references unknown enum type '$n'"
                        )
                    case TypeRef.ArrayT(TypeRef.BooleanT) =>
                        // Array[Boolean] has no portable C representation: Scala/JVM use 1 byte/elem packed (or
                        // BitSet-like), Scala Native uses CBool (1 byte), and koffi has no boolean[] type.
                        // Marshalling would require per-element conversion which we choose not to implement,                        // users are expected to use Array[Byte] with the 0/1 convention instead.
                        entries += Entry(
                            Severity.Syntactic,
                            member,
                            s"$loc: parameter '${p.name}' has type Array[Boolean] which is not supported. " +
                                "Use Array[Byte] with the 0/1 convention instead (per-element conversion is intentionally not emitted)."
                        )
                    case _ => ()
            }

            // Multi-value return must have >=2 fields.
            m.returnShape match
                case ReturnShape.MultiValue(mv) if mv.fields.size < 2 =>
                    entries += Entry(
                        Severity.Structural,
                        member,
                        s"$loc: multi-value return '${mv.simpleName}' requires >=2 fields, found ${mv.fields.size}"
                    )
                case ReturnShape.Struct(st) if st.fields.isEmpty =>
                    entries += Entry(
                        Severity.Structural,
                        member,
                        s"$loc: struct return '${st.simpleName}' has no fields"
                    )
                case _ => ()
            end match

            // Borrowed Buffer[A] returns require the size parameter to exist on this method.
            m.returnShape match
                case ReturnShape.BorrowedBuffer(_, sizeParam) =>
                    if !m.params.exists(_.name == sizeParam) then
                        entries += Entry(
                            Severity.Structural,
                            member,
                            s"$loc: Borrowed[Buffer[...]] inferred size parameter '$sizeParam' does not exist on this method. " +
                                s"Declared parameters: ${m.params.map(_.name).mkString("[", ", ", "]")}."
                        )
                case _ => ()
            end match

            // Multi-value return: the FIRST field becomes the C return value, so it must be a primitive.
            m.returnShape match
                case ReturnShape.MultiValue(mvSpec) if mvSpec.fields.nonEmpty =>
                    val head = mvSpec.fields.head
                    if !TypeRef.isPrimitive(head.tpe) then
                        entries += Entry(
                            Severity.Structural,
                            member,
                            s"${spec.fqcn}.${m.scalaName}: multi-value return case class `${mvSpec.simpleName}` has a non-primitive first field (${head.tpe}). The first field becomes the C return value, must be a primitive."
                        )
                    end if
                case _ => ()
            end match

            // Callback classification: Retained must have exactly one Guard; Transient must have zero.
            m.callbackKind match
                case CallbackKind.Retained =>
                    val guards = m.params.count(_.tpe == TypeRef.GuardT)
                    if guards != 1 then
                        entries += Entry(
                            Severity.Structural,
                            member,
                            s"$loc: retained callback method must have exactly one Ffi.Guard parameter, found $guards"
                        )
                    end if
                    if !m.params.exists(p => p.tpe.isInstanceOf[TypeRef.FnPtrT]) then
                        entries += Entry(
                            Severity.Structural,
                            member,
                            s"$loc: retained callback method has no function parameter"
                        )
                    end if
                case CallbackKind.Transient =>
                    val guards = m.params.count(_.tpe == TypeRef.GuardT)
                    if guards != 0 then
                        entries += Entry(
                            Severity.Structural,
                            member,
                            s"$loc: transient callback method must not have an Ffi.Guard parameter"
                        )
                    end if
                    if !m.params.exists(p => p.tpe.isInstanceOf[TypeRef.FnPtrT]) then
                        entries += Entry(
                            Severity.Structural,
                            member,
                            s"$loc: transient callback method has no function parameter"
                        )
                    end if
                case CallbackKind.None =>
                    val guards = m.params.count(_.tpe == TypeRef.GuardT)
                    if guards != 0 then
                        entries += Entry(
                            Severity.Structural,
                            member,
                            s"$loc: method has an Ffi.Guard parameter but no callback, kind should be Retained"
                        )
                    end if
            end match
        end for

        // Circular-reference detection: reject any struct that can reach itself through a chain of `StructT` fields. This covers
        // direct self-reference (`S { next: S }`), two-hop (`A { b: B }; B { a: A }`), and deeper cycles. A DAG where multiple paths
        // lead to the same struct is NOT a cycle and is accepted. (Critical, emitters would loop forever or stack-overflow.)
        for s <- spec.structs do
            if reachesSelf(s, structsByName) then
                entries += Entry(Severity.Critical, s.fqcn, s"struct ${s.fqcn} transitively contains itself")

        // Struct Buffer[A] size inference: for every struct that contains at least one Buffer[A] field, the enclosing
        // struct must have exactly one Int/Long sibling. Mirrors the top-level `Borrowed[Buffer[A]]` rule in FfiInspector.
        // Kept in sync with FfiGenErrors.{missingBorrowedBufferSize,ambiguousBorrowedBufferSize}.
        for s <- spec.structs do
            val bufferFields = s.fields.filter(_.tpe.isInstanceOf[TypeRef.BufferT])
            if bufferFields.nonEmpty then
                val candidates = s.intLongSizeCandidates
                candidates match
                    case Nil =>
                        bufferFields.foreach { bf =>
                            entries += Entry(
                                Severity.Syntactic,
                                s.fqcn,
                                s"[kyo-ffi] struct '${s.fqcn}' field '${bf.name}' is a Buffer[...] but the struct has no Int or Long sibling field to infer size from. " +
                                    "Add exactly one Int or Long field to this struct (used as the buffer size)."
                            )
                        }
                    case _ :: Nil => ()
                    case multiple =>
                        bufferFields.foreach { bf =>
                            val names = multiple.map(_.name)
                            entries += Entry(
                                Severity.Syntactic,
                                s.fqcn,
                                s"[kyo-ffi] struct '${s.fqcn}' field '${bf.name}' is a Buffer[...] but the struct has ${names.size} Int/Long sibling fields (${names.mkString(", ")}), size inference is ambiguous. " +
                                    "Reduce to exactly one Int/Long field, or split the struct."
                            )
                        }
                end match
            end if
        end for

        // Stable order: severity bucket first (Critical < Structural < Syntactic), then member FQN, then message text.
        // The secondary keys are deterministic regardless of TASTy member traversal order.
        entries
            .result()
            .sortBy(e => (e.severity.ordinal, e.member, e.message))
            .map(_.message)
    end validate

    /** True iff [[root]] can reach itself by following [[TypeRef.StructT]] fields. Uses DFS with a per-search active stack, `onStack`
      * tracks ancestors on the current path, so revisiting a struct via a sibling branch (DAG shape) is not mistaken for a cycle.
      */
    private def reachesSelf(
        root: StructSpec,
        all: Map[String, StructSpec]
    ): Boolean =
        val rootName = root.fqcn

        def dfs(current: StructSpec, onStack: Set[String]): Boolean =
            current.fields.exists { f =>
                f.tpe match
                    case TypeRef.StructT(name) =>
                        if name == rootName then true
                        else if onStack.contains(name) then false // cycle among other structs, but not involving root
                        else
                            all.get(name) match
                                case Some(child) => dfs(child, onStack + name)
                                case None        => false
                    case _ => false
            }
        end dfs

        dfs(root, Set(rootName))
    end reachesSelf
end TypeValidator
