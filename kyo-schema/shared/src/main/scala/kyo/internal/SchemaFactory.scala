package kyo.internal

import kyo.*
import kyo.Codec.Reader
import kyo.Codec.Writer

/** Factory methods for constructing Schema instances.
  *
  * All Schema construction goes through these methods. The macro-generated code (SchemaDerivedMacro, SchemaTransformMacro, FocusMacro,
  * CodecMacro) references these methods to emit Schema construction expressions.
  */
private[kyo] object SchemaFactory:

    /** Internal factory for macro-generated Schema instances. Not part of public API.
      *
      * The F type parameter becomes the Focused type member on the returned Schema.
      */
    def create[A, F](
        getter: A => Maybe[F],
        setter: (A, F) => A,
        segments: Seq[String],
        sourceFields: Seq[Field[?, ?]] = Seq.empty
    ): Schema[A] { type Focused = F } =
        (new Schema[A](
            getter = getter.asInstanceOf[A => Maybe[Any]],
            setter = setter.asInstanceOf[(A, Any) => A],
            segments = segments,
            sourceFields = sourceFields
        )).asInstanceOf[Schema[A] { type Focused = F }]

    /** Internal factory for macro-generated Schema instances with serialization. */
    def create[A, F](
        getter: A => Maybe[F],
        setter: (A, F) => A,
        segments: Seq[String],
        sourceFields: Seq[Field[?, ?]],
        writeFn: (A, Writer) => Unit,
        readFn: Reader => A
    ): Schema[A] { type Focused = F } =
        (new Schema[A](
            getter = getter.asInstanceOf[A => Maybe[Any]],
            setter = setter.asInstanceOf[(A, Any) => A],
            segments = segments,
            sourceFields = sourceFields,
            serializeWrite = Maybe(writeFn),
            serializeRead = Maybe(readFn)
        )).asInstanceOf[Schema[A] { type Focused = F }]

    /** Creates a primitive Schema with hand-written serialization functions. */
    def primitive[A](
        writeFn: (A, Writer) => Unit,
        readFn: Reader => A
    ): Schema[A] =
        (new Schema[A](
            getter = (a: A) => Maybe(a).asInstanceOf[Maybe[Any]],
            setter = (_: A, v: Any) => v.asInstanceOf[A],
            segments = Seq.empty,
            serializeWrite = Maybe(writeFn),
            serializeRead = Maybe(readFn)
        )).asInstanceOf[Schema[A]]

    /** Internal factory for transform macros. Copies internal state from a source Schema. Not part of public API.
      *
      * Casts are needed because the transform changes Focused but the underlying getter/setter still operates on A's real runtime
      * structure. This is the ONE place where casts are justified -- the transform only changes what the type system sees, not the runtime
      * representation.
      */
    def createFrom[A, F2](
        source: Schema[A],
        checks: Seq[A => Seq[ValidationFailedException]],
        fieldTransforms: Chunk[(String, Any => Any)],
        computedFields: Chunk[(String, A => Any)],
        renamedFields: Chunk[(String, String)],
        droppedFields: Set[String] = Set.empty
    ): Schema[A] { type Focused = F2 } =
        // Compute updated field metadata:
        // - drop removes all path-keyed entries whose first segment is the dropped field
        // - rename updates the first segment of all path-keyed entries from old name to new name
        def dropPathKeys[V](m: Map[Seq[String], V], dropped: Set[String]): Map[Seq[String], V] =
            m.filter { case (path, _) => path.isEmpty || !dropped.contains(path.head) }
        def renamePathKey[V](m: Map[Seq[String], V], from: String, to: String): Map[Seq[String], V] =
            m.map { case (path, v) =>
                if path.nonEmpty && path.head == from then (to +: path.tail) -> v
                else path                                                    -> v
            }
        val updatedFieldDocs =
            val afterDrop  = dropPathKeys(source.fieldDocs, droppedFields)
            val newRenames = renamedFields.drop(source.renamedFields.length)
            newRenames.foldLeft(afterDrop) { (docs, rename) =>
                renamePathKey(docs, rename._1, rename._2)
            }
        end updatedFieldDocs
        val updatedFieldDeprecated =
            val afterDrop  = dropPathKeys(source.fieldDeprecated, droppedFields)
            val newRenames = renamedFields.drop(source.renamedFields.length)
            newRenames.foldLeft(afterDrop) { (deps, rename) =>
                renamePathKey(deps, rename._1, rename._2)
            }
        end updatedFieldDeprecated
        // Update field IDs: drop removed fields, rename keys
        val updatedFieldIds =
            val afterDrop  = dropPathKeys(source.fieldIdOverrides, droppedFields)
            val newRenames = renamedFields.drop(source.renamedFields.length)
            newRenames.foldLeft(afterDrop) { (ids, rename) =>
                renamePathKey(ids, rename._1, rename._2)
            }
        end updatedFieldIds
        (new Schema[A](
            getter = source.getter,
            setter = source.setter,
            segments = source.segments,
            examples = source.examples,
            fieldDocs = updatedFieldDocs,
            fieldDeprecated = updatedFieldDeprecated,
            constraints = source.constraints,
            droppedFields = source.droppedFields ++ droppedFields,
            renamedFields = renamedFields,
            computedFields = computedFields,
            fieldTransforms = fieldTransforms,
            sourceFields = source.sourceFields,
            checks = checks,
            documentation = source.documentation,
            fieldIdOverrides = updatedFieldIds,
            serializeWrite = source.serializeWrite,
            serializeRead = source.serializeRead,
            discriminatorField = source.discriminatorField
        )).asInstanceOf[Schema[A] { type Focused = F2 }]
    end createFrom

    /** Internal factory for creating Schema with a specific Focused type, preserving all state. Used by methods that return
      * `Schema[A] { type Focused = E }` without changing E.
      */
    def createWithFocused[A, E](
        getter: A => Maybe[Any],
        setter: (A, Any) => A,
        segments: Seq[String],
        checks: Seq[A => Seq[ValidationFailedException]],
        fieldTransforms: Chunk[(String, Any => Any)],
        computedFields: Chunk[(String, A => Any)],
        renamedFields: Chunk[(String, String)],
        sourceFields: Seq[Field[?, ?]],
        droppedFields: Set[String],
        doc: Maybe[String] = Maybe.empty,
        fieldDocs: Map[Seq[String], String] = Map.empty,
        examples: Chunk[A] = Chunk.empty,
        fieldDeprecated: Map[Seq[String], String] = Map.empty,
        constraints: Seq[Schema.Constraint] = Seq.empty,
        fieldIds: Map[Seq[String], Int] = Map.empty,
        serializeWrite: Maybe[(A, kyo.Codec.Writer) => Unit] = Maybe.empty,
        serializeRead: Maybe[kyo.Codec.Reader => A] = Maybe.empty,
        discriminatorField: Maybe[String] = Maybe.empty
    ): Schema[A] { type Focused = E } =
        (new Schema[A](
            getter = getter,
            setter = setter,
            segments = segments,
            examples = examples,
            fieldDocs = fieldDocs,
            fieldDeprecated = fieldDeprecated,
            constraints = constraints,
            droppedFields = droppedFields,
            renamedFields = renamedFields,
            computedFields = computedFields,
            fieldTransforms = fieldTransforms,
            sourceFields = sourceFields,
            checks = checks,
            documentation = doc,
            fieldIdOverrides = fieldIds,
            serializeWrite = serializeWrite,
            serializeRead = serializeRead,
            discriminatorField = discriminatorField
        )).asInstanceOf[Schema[A] { type Focused = E }]

end SchemaFactory
