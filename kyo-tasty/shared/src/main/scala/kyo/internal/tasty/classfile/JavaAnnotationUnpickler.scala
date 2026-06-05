package kyo.internal.tasty.classfile

import kyo.*
import kyo.Tasty.Java.Annotation as JavaAnnotation
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.symbol.Symbol as SymbolFactory
import kyo.internal.tasty.symbol.SymbolDescriptor
import kyo.internal.tasty.symbol.SymbolKind
import kyo.internal.tasty.symbol.TypedSymbolFactory

/** Decodes RuntimeVisibleAnnotations and RuntimeInvisibleAnnotations attribute bodies into JavaAnnotation values.
  *
  * The caller passes a ByteView already positioned at the annotation data (after the attribute_name_index and attribute_length fields have
  * been consumed). Invoked twice per symbol (visible and invisible), then results are concatenated.
  *
  * Cross-platform: pure byte arithmetic, no JVM I/O.
  *
  * Reference: JVMS §4.7.16 RuntimeVisibleAnnotations; §4.7.16.1 element_value.
  */
object JavaAnnotationUnpickler:

    import ConstantPool.readU1
    import ConstantPool.readU2

    private val MaxAnnotationDepth: Int = 8

    /** Read all annotations from the attribute body starting at the current view cursor.
      *
      * The view must be positioned at the u2 num_annotations field (the first byte of annotation data after the attribute header has been
      * consumed).
      *
      * Note: classfile annotations resolve class references via CONSTANT_Class pool entries, not by TASTy addresses. Unresolved symbols for
      * annotation class references are created directly (Phase 07: ClasspathRef deleted).
      */
    def readAnnotations(
        view: ByteView,
        pool: ConstantPool
    )(using Frame, AllowUnsafe): Chunk[JavaAnnotation] < (Sync & Abort[TastyError]) =
        Sync.defer(readU2(view)).map: numAnnotations =>
            readAnnotationList(view, pool, numAnnotations, 0, Chunk.empty, depth = 0)

    private def readAnnotationList(
        view: ByteView,
        pool: ConstantPool,
        total: Int,
        idx: Int,
        acc: Chunk[JavaAnnotation],
        depth: Int
    )(using Frame, AllowUnsafe): Chunk[JavaAnnotation] < (Sync & Abort[TastyError]) =
        if idx >= total then acc
        else
            readOneAnnotation(view, pool, depth).map: ann =>
                readAnnotationList(view, pool, total, idx + 1, acc.appended(ann), depth)

    private[classfile] def readOneAnnotation(
        view: ByteView,
        pool: ConstantPool,
        depth: Int
    )(using Frame, AllowUnsafe): JavaAnnotation < (Sync & Abort[TastyError]) =
        if depth > MaxAnnotationDepth then
            Abort.fail(TastyError.ClassfileFormatError(
                pool.path,
                s"Annotation nesting depth exceeds maximum ($MaxAnnotationDepth)",
                view.position
            ))
        else
            Sync.defer(readU2(view)).map: typeIdx =>
                pool.utf8(typeIdx).map: typeDescriptor =>
                    val annotationClassSym = descriptorToUnresolvedSymbol(typeDescriptor)
                    Sync.defer(readU2(view)).map: numPairs =>
                        readElementValuePairs(view, pool, numPairs, 0, Chunk.empty, depth).map: values =>
                            JavaAnnotation(annotationClassSym, values)

    private def readElementValuePairs(
        view: ByteView,
        pool: ConstantPool,
        total: Int,
        idx: Int,
        acc: Chunk[(Tasty.Name, JavaAnnotation.Value)],
        depth: Int
    )(using Frame, AllowUnsafe): Chunk[(Tasty.Name, JavaAnnotation.Value)] < (Sync & Abort[TastyError]) =
        if idx >= total then acc
        else
            Sync.defer(readU2(view)).map: nameIdx =>
                pool.utf8(nameIdx).map: elemName =>
                    val key = Tasty.Name(elemName)
                    readElementValue(view, pool, depth).map: value =>
                        readElementValuePairs(view, pool, total, idx + 1, acc.append((key, value)), depth)

    private def readElementValue(
        view: ByteView,
        pool: ConstantPool,
        depth: Int
    )(using Frame, AllowUnsafe): JavaAnnotation.Value < (Sync & Abort[TastyError]) =
        Sync.defer(readU1(view)).map: tag =>
            tag match
                case 'B' =>
                    // byte: stored as CONSTANT_Integer; narrow to byte
                    Sync.defer(readU2(view)).map: idx =>
                        pool.integer(idx).map(v => JavaAnnotation.Value.IntVal(v.toByte.toInt))

                case 'C' =>
                    // char: stored as CONSTANT_Integer; raw int value
                    Sync.defer(readU2(view)).map: idx =>
                        pool.integer(idx).map(v => JavaAnnotation.Value.IntVal(v))

                case 'D' =>
                    // double: stored as CONSTANT_Double.
                    Sync.defer(readU2(view)).map: idx =>
                        pool.double_(idx).map(v => JavaAnnotation.Value.DoubleVal(v))

                case 'F' =>
                    // float: stored as CONSTANT_Float.
                    Sync.defer(readU2(view)).map: idx =>
                        pool.float_(idx).map(v => JavaAnnotation.Value.FloatVal(v))

                case 'I' =>
                    // int: CONSTANT_Integer
                    Sync.defer(readU2(view)).map: idx =>
                        pool.integer(idx).map(v => JavaAnnotation.Value.IntVal(v))

                case 'J' =>
                    // long: CONSTANT_Long
                    Sync.defer(readU2(view)).map: idx =>
                        pool.long_(idx).map(v => JavaAnnotation.Value.LongVal(v))

                case 'S' =>
                    // short: CONSTANT_Integer; narrow to short
                    Sync.defer(readU2(view)).map: idx =>
                        pool.integer(idx).map(v => JavaAnnotation.Value.IntVal(v.toShort.toInt))

                case 'Z' =>
                    // boolean: CONSTANT_Integer; 0=false, 1=true
                    Sync.defer(readU2(view)).map: idx =>
                        pool.integer(idx).map(v => JavaAnnotation.Value.BoolVal(v != 0))

                case 's' =>
                    // String: CONSTANT_Utf8
                    Sync.defer(readU2(view)).map: idx =>
                        pool.utf8(idx).map(s => JavaAnnotation.Value.StringVal(s))

                case 'e' =>
                    // Enum constant
                    Sync.defer {
                        val typeNameIdx  = readU2(view)
                        val constNameIdx = readU2(view)
                        (typeNameIdx, constNameIdx)
                    }.map: (typeNameIdx, constNameIdx) =>
                        pool.utf8(typeNameIdx).map: typeDescriptor =>
                            pool.utf8(constNameIdx).map: constName =>
                                val enumTypeSym = descriptorToUnresolvedSymbol(typeDescriptor)
                                JavaAnnotation.Value.EnumVal(enumTypeSym, Tasty.Name(constName))

                case 'c' =>
                    // Class literal: cp -> Utf8 class descriptor e.g. "Ljava/lang/String;"
                    Sync.defer(readU2(view)).map: idx =>
                        pool.utf8(idx).map: classDesc =>
                            val tpe = descriptorToType(classDesc)
                            JavaAnnotation.Value.ClassVal(tpe)

                case '@' =>
                    // Nested annotation
                    readOneAnnotation(view, pool, depth + 1).map: nested =>
                        JavaAnnotation.Value.AnnotationVal(nested)

                case '[' =>
                    // Array of element_values
                    Sync.defer(readU2(view)).map: numValues =>
                        readElementValueArray(view, pool, numValues, 0, Chunk.empty, depth).map: elems =>
                            JavaAnnotation.Value.ArrayVal(elems)

                case unknown =>
                    Abort.fail(TastyError.ClassfileFormatError(
                        pool.path,
                        s"Unknown annotation element_value tag: ${unknown.toChar} (0x${unknown.toHexString})",
                        view.position
                    ))
    end readElementValue

    private def readElementValueArray(
        view: ByteView,
        pool: ConstantPool,
        total: Int,
        idx: Int,
        acc: Chunk[JavaAnnotation.Value],
        depth: Int
    )(using Frame, AllowUnsafe): Chunk[JavaAnnotation.Value] < (Sync & Abort[TastyError]) =
        if idx >= total then acc
        else
            readElementValue(view, pool, depth).map: v =>
                readElementValueArray(view, pool, total, idx + 1, acc.appended(v), depth)

    /** Convert a field descriptor like "Ljava/lang/Deprecated;" to an unresolved Tasty.Symbol (id=-1). */
    private def descriptorToUnresolvedSymbol(descriptor: String)(using AllowUnsafe): Tasty.Symbol =
        val fqn = descriptorToFqn(descriptor)
        TypedSymbolFactory.from(new SymbolDescriptor(
            id = -1,
            kind = SymbolKind.Class,
            flags = Tasty.Flags.empty,
            name = Tasty.Name(fqn),
            ownerId = -1,
            declaredType = Maybe.Absent,
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            body = Maybe.Absent
        ))
    end descriptorToUnresolvedSymbol

    /** Convert a class descriptor to a Tasty.Type.Named wrapping the sentinel unresolved id. */
    private def descriptorToType(descriptor: String)(using AllowUnsafe): Tasty.Type =
        // Sentinel id -1 for annotation type references (not resolved against classpath here).
        Tasty.Type.Named(kyo.Tasty.SymbolId(-1))

    /** Strip "L" prefix and ";" suffix, replace "/" with ".". For non-L descriptors (primitives), return as-is.
      */
    private def descriptorToFqn(descriptor: String): String =
        if descriptor.startsWith("L") && descriptor.endsWith(";") then
            descriptor.substring(1, descriptor.length - 1).replace('/', '.')
        else
            descriptor

end JavaAnnotationUnpickler
