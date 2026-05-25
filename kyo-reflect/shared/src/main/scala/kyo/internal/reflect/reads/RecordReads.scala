package kyo.internal.reflect.reads

import kyo.*
import kyo.Reflect.*

/** Built-in Reflect.Reads instance for Record[F].
  *
  * Given a Fields[F] instance, builds a Reads[Record[F]] that delegates each read to Reflect.symbolToRecord[F]. The touchedFields is
  * computed at instance-creation time by walking the field names in F and unioning the corresponding FieldSet bits.
  *
  * Exported from Reflect.Reads companion so it is automatically in implicit scope.
  */
object RecordReads:

    /** Maps a field name (from the accessor table in DESIGN.md Section 12) to its FieldSet bit. */
    private def fieldSetForName(name: String): Reflect.FieldSet =
        name match
            case "name"            => Reflect.FieldSet.Name
            case "binaryName"      => Reflect.FieldSet.BinaryName
            case "flags"           => Reflect.FieldSet.Flags
            case "kind"            => Reflect.FieldSet.Kind
            case "owner"           => Reflect.FieldSet.Owner
            case "isInline"        => Reflect.FieldSet.Flags
            case "isContextual"    => Reflect.FieldSet.Flags
            case "isOpaque"        => Reflect.FieldSet.Flags
            case "isPackageObject" => Reflect.FieldSet.Flags
            case "isModule"        => Reflect.FieldSet.Flags
            case "isJava"          => Reflect.FieldSet.Flags
            case "declaredType"    => Reflect.FieldSet.DeclaredType
            case "parents"         => Reflect.FieldSet.Parents
            case "typeParams"      => Reflect.FieldSet.TypeParams
            case "declarations"    => Reflect.FieldSet.Members
            case "companion"       => Reflect.FieldSet.Companion
            case "javaSpecific"    => Reflect.FieldSet.JavaSpecific
            case _                 => Reflect.FieldSet.Empty

    given recordReads[F](using fields: Fields[F]): Reflect.Reads[Record[F]] =
        val tf = fields.fields.foldLeft(Reflect.FieldSet.Empty) { (acc, field) =>
            acc | fieldSetForName(field.name)
        }
        new Reflect.Reads[Record[F]]:
            val symbolKinds: Set[Reflect.SymbolKind] = Set(Reflect.SymbolKind.values*)
            val needsBodies: Boolean                 = false
            val touchedFields: Reflect.FieldSet      = tf
            def read(sym: Reflect.Symbol)(using Frame): Record[F] < (Sync & Abort[ReflectError]) =
                Reflect.symbolToRecord[F](sym)
        end new
    end recordReads

end RecordReads
