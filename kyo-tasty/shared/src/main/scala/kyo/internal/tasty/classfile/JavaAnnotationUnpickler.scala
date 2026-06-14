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
      * Note: classfile annotations resolve class references via CONSTANT_Class pool entries, not by TASTy
      * addresses. Unresolved symbols for annotation class references are created directly.
      */
    def readAnnotations(
        view: ByteView,
        pool: ConstantPool
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[JavaAnnotation]] =
        val numAnnotations = readU2(view)
        readAnnotationList(view, pool, numAnnotations, 0, Chunk.empty, depth = 0)
    end readAnnotations

    private def readAnnotationList(
        view: ByteView,
        pool: ConstantPool,
        total: Int,
        idx: Int,
        acc: Chunk[JavaAnnotation],
        depth: Int
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[JavaAnnotation]] =
        if idx >= total then Result.Success(acc)
        else
            readOneAnnotation(view, pool, depth).flatMap { annotation =>
                readAnnotationList(view, pool, total, idx + 1, acc.appended(annotation), depth)
            }

    private[classfile] def readOneAnnotation(
        view: ByteView,
        pool: ConstantPool,
        depth: Int
    )(using Frame, AllowUnsafe): Result[TastyError, JavaAnnotation] =
        if depth > MaxAnnotationDepth then
            Result.Failure(TastyError.ClassfileFormatError(
                pool.path,
                s"Annotation nesting depth exceeds maximum ($MaxAnnotationDepth)",
                view.position
            ))
        else
            val typeIdx = readU2(view)
            pool.utf8Unsafe(typeIdx).flatMap { typeDescriptor =>
                val annotationClassSym = descriptorToUnresolvedSymbol(typeDescriptor)
                val numPairs           = readU2(view)
                readElementValuePairs(view, pool, numPairs, 0, Chunk.empty, depth).map { values =>
                    JavaAnnotation(annotationClassSym, values, Tasty.Name(descriptorToFullName(typeDescriptor)))
                }
            }

    private def readElementValuePairs(
        view: ByteView,
        pool: ConstantPool,
        total: Int,
        idx: Int,
        acc: Chunk[(Tasty.Name, JavaAnnotation.Value)],
        depth: Int
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[(Tasty.Name, JavaAnnotation.Value)]] =
        if idx >= total then Result.Success(acc)
        else
            val nameIdx = readU2(view)
            pool.utf8Unsafe(nameIdx).flatMap { elemName =>
                val key = Tasty.Name(elemName)
                readElementValue(view, pool, depth).flatMap { value =>
                    readElementValuePairs(view, pool, total, idx + 1, acc.append((key, value)), depth)
                }
            }

    private def readElementValue(
        view: ByteView,
        pool: ConstantPool,
        depth: Int
    )(using Frame, AllowUnsafe): Result[TastyError, JavaAnnotation.Value] =
        val tag = readU1(view)
        tag match
            case 'B' =>
                // byte: stored as CONSTANT_Integer; narrow to byte
                val idx = readU2(view)
                pool.integerUnsafe(idx).map(v => JavaAnnotation.Value.IntVal(v.toByte.toInt))

            case 'C' =>
                // char: stored as CONSTANT_Integer; raw int value
                val idx = readU2(view)
                pool.integerUnsafe(idx).map(v => JavaAnnotation.Value.IntVal(v))

            case 'D' =>
                // double: stored as CONSTANT_Double.
                val idx = readU2(view)
                pool.doubleUnsafe(idx).map(v => JavaAnnotation.Value.DoubleVal(v))

            case 'F' =>
                // float: stored as CONSTANT_Float.
                val idx = readU2(view)
                pool.floatUnsafe(idx).map(v => JavaAnnotation.Value.FloatVal(v))

            case 'I' =>
                // int: CONSTANT_Integer
                val idx = readU2(view)
                pool.integerUnsafe(idx).map(v => JavaAnnotation.Value.IntVal(v))

            case 'J' =>
                // long: CONSTANT_Long
                val idx = readU2(view)
                pool.longUnsafe(idx).map(v => JavaAnnotation.Value.LongVal(v))

            case 'S' =>
                // short: CONSTANT_Integer; narrow to short
                val idx = readU2(view)
                pool.integerUnsafe(idx).map(v => JavaAnnotation.Value.IntVal(v.toShort.toInt))

            case 'Z' =>
                // boolean: CONSTANT_Integer; 0=false, 1=true
                val idx = readU2(view)
                pool.integerUnsafe(idx).map(v => JavaAnnotation.Value.BoolVal(v != 0))

            case 's' =>
                // String: CONSTANT_Utf8
                val idx = readU2(view)
                pool.utf8Unsafe(idx).map(s => JavaAnnotation.Value.StringVal(s))

            case 'e' =>
                // Enum constant
                val typeNameIdx  = readU2(view)
                val constNameIdx = readU2(view)
                pool.utf8Unsafe(typeNameIdx).flatMap { typeDescriptor =>
                    pool.utf8Unsafe(constNameIdx).map { constName =>
                        val enumTypeSym = descriptorToUnresolvedSymbol(typeDescriptor)
                        JavaAnnotation.Value.EnumVal(enumTypeSym, Tasty.Name(constName))
                    }
                }

            case 'c' =>
                // Class literal: classpath -> Utf8 class descriptor e.g. "Ljava/lang/String;"
                val idx = readU2(view)
                pool.utf8Unsafe(idx).map { classDesc =>
                    val tpe = descriptorToType(classDesc)
                    JavaAnnotation.Value.ClassVal(tpe)
                }

            case '@' =>
                // Nested annotation
                readOneAnnotation(view, pool, depth + 1).map { nested =>
                    JavaAnnotation.Value.AnnotationVal(nested)
                }

            case '[' =>
                // Array of element_values
                val numValues = readU2(view)
                readElementValueArray(view, pool, numValues, 0, Chunk.empty, depth).map { elems =>
                    JavaAnnotation.Value.ArrayVal(elems)
                }

            case unknown =>
                Result.Failure(TastyError.ClassfileFormatError(
                    pool.path,
                    s"Unknown annotation element_value tag: ${unknown.toChar} (0x${unknown.toHexString})",
                    view.position
                ))
        end match
    end readElementValue

    private def readElementValueArray(
        view: ByteView,
        pool: ConstantPool,
        total: Int,
        idx: Int,
        acc: Chunk[JavaAnnotation.Value],
        depth: Int
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[JavaAnnotation.Value]] =
        if idx >= total then Result.Success(acc)
        else
            readElementValue(view, pool, depth).flatMap { v =>
                readElementValueArray(view, pool, total, idx + 1, acc.appended(v), depth)
            }

    /** Convert a field descriptor like "Ljava/lang/Deprecated;" to an unresolved Tasty.Symbol (id=-1). */
    private def descriptorToUnresolvedSymbol(descriptor: String)(using AllowUnsafe): Tasty.Symbol =
        val fullName = descriptorToFullName(descriptor)
        TypedSymbolFactory.from(new SymbolDescriptor(
            id = -1,
            kind = SymbolKind.Class,
            flags = Tasty.Flags.empty,
            name = Tasty.Name(fullName),
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

    /** Convert a class descriptor to a Tasty.Type for a Java annotation class-literal value.
      *
      * The descriptor is a JVM class descriptor (e.g. "Ljava/lang/String;"). At decode time the
      * classpath fullNameIndex is not available, so the type cannot be resolved to a real SymbolId.
      * Encodes the dotted fully-qualified name as a TermRef name so consumers can extract the class name via
      * pattern-matching on Type.TermRef without encountering a Named(SymbolId(-1)) sentinel.
      * Matches the SnapshotReader warm-load encoding for annotation fully-qualified names.
      */
    private def descriptorToType(descriptor: String)(using AllowUnsafe): Tasty.Type =
        val fullName = descriptorToFullName(descriptor)
        Tasty.Type.TermRef(Tasty.Type.Tuple(Chunk.empty), Tasty.Name(fullName))

    /** Strip "L" prefix and ";" suffix, replace "/" with ".". For non-L descriptors (primitives), return as-is.
      */
    private def descriptorToFullName(descriptor: String): String =
        if descriptor.startsWith("L") && descriptor.endsWith(";") then
            descriptor.substring(1, descriptor.length - 1).replace('/', '.')
        else
            descriptor

end JavaAnnotationUnpickler
