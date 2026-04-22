package kyo.internal

import kyo.*
import kyo.Codec.Reader
import kyo.Codec.Writer
import scala.annotation.nowarn

/** Factory methods for constructing Schema instances.
  *
  * The inline factory methods on `Schema` (`Schema.init`, `Schema.initFocused`, `Schema.create`, `Schema.createWithFocused`) are the single
  * supported construction surface. This object retains only the path-key metadata recomputation required by `createFrom`, which handles
  * transform macros (drop/rename/add) that mutate fieldDocs / fieldDeprecated / fieldIdOverrides keys.
  */
private[kyo] object SchemaFactory:

    /** Internal factory for transform macros. Copies internal state from a source Schema while threading the source's abstract codec/focus
      * methods into the new Schema via `Schema.initFocused`. Not part of public API.
      *
      * Recomputes path-keyed metadata (`fieldDocs`, `fieldDeprecated`, `fieldIdOverrides`) to reflect the newly-applied drop/rename
      * operations.
      */
    @nowarn("msg=anonymous")
    def createFrom[A, F2](
        source: Schema[A],
        checks: Seq[A => Seq[ValidationFailedException]],
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
        Schema.initFocused[A, F2](
            writeFn = (a: A, w: Writer) => source.serializeWrite(a, w),
            readFn = (r: Reader) => source.serializeRead(r),
            getterFn = (a: A) => source.getter(a).asInstanceOf[Maybe[F2]],
            setterFn = (a: A, v: F2) => source.setter(a, v),
            segments = source.segments,
            sourceFields = source.sourceFields,
            examples = source.examples,
            fieldDocs = updatedFieldDocs,
            fieldDeprecated = updatedFieldDeprecated,
            constraints = source.constraints,
            droppedFields = source.droppedFields ++ droppedFields,
            renamedFields = renamedFields,
            computedFields = computedFields,
            checks = checks,
            documentation = source.documentation,
            fieldIdOverrides = updatedFieldIds,
            discriminatorField = source.discriminatorField
        )
    end createFrom

end SchemaFactory
