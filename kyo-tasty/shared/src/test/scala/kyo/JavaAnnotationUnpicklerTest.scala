package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.classfile.ClassfileFormat
import kyo.internal.tasty.classfile.ConstantPool
import kyo.internal.tasty.classfile.JavaAnnotationUnpickler

/** Tests for JavaAnnotationUnpickler.
  *
  * All tests are in shared/ (cross-platform). JavaAnnotationUnpickler is pure byte arithmetic with no JVM I/O.
  */
class JavaAnnotationUnpicklerTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    /** Build a constant pool byte stream from a sequence of string literals.
      *
      * Each string becomes a CONSTANT_Utf8 entry at the corresponding slot (1-based). The u2 count field is set to strings.length + 1.
      */
    private def buildUtf8OnlyPool(strings: String*): Array[Byte] =
        val encoded   = strings.map(_.getBytes("UTF-8"))
        val totalSize = 2 + encoded.foldLeft(0)((acc, bs) => acc + 1 + 2 + bs.length)
        val buffer    = new Array[Byte](totalSize)
        // count = strings.length + 1 (slots 1.strings.length)
        val count = strings.length + 1
        buffer(0) = ((count >> 8) & 0xff).toByte
        buffer(1) = (count & 0xff).toByte
        var pos = 2
        encoded.foreach { bs =>
            buffer(pos) = ClassfileFormat.CONSTANT_Utf8.toByte
            buffer(pos + 1) = ((bs.length >> 8) & 0xff).toByte
            buffer(pos + 2) = (bs.length & 0xff).toByte
            var i = 0
            while i < bs.length do
                buffer(pos + 3 + i) = bs(i)
                i += 1
            pos += 1 + 2 + bs.length
        }
        buffer
    end buildUtf8OnlyPool

    "readAnnotations decodes @Deprecated (no pairs) into JavaAnnotation with name java.lang.Deprecated" in {
        val poolBytes = buildUtf8OnlyPool("Ljava/lang/Deprecated;")
        val poolView  = ByteView(poolBytes)
        ConstantPool.read(poolView, "<test>") match
            case Result.Failure(err) => fail(s"Pool read failed: $err")
            case Result.Panic(ex)    => fail(s"Pool read panicked: $ex")
            case Result.Success(pool) =>
                val annBytes = Array[Byte](
                    0x00, 0x01, // num_annotations = 1
                    0x00, 0x01, // type_index = 1 (Utf8 "Ljava/lang/Deprecated;")
                    0x00, 0x00  // num_element_value_pairs = 0
                )
                val annView = ByteView(annBytes)
                JavaAnnotationUnpickler.readAnnotations(annView, pool) match
                    case Result.Failure(err) => fail(s"readAnnotations failed: $err")
                    case Result.Panic(ex)    => fail(s"readAnnotations panicked: $ex")
                    case Result.Success(anns) =>
                        assert(anns.size == 1, s"Expected 1 annotation, got ${anns.size}")
                        val annotation = anns.head
                        val fullName   = annotation.annotationClass.name.asString
                        assert(
                            fullName == "java.lang.Deprecated",
                            s"Expected annotationClass name 'java.lang.Deprecated', got '$fullName'"
                        )
                        assert(annotation.values.isEmpty, s"Expected empty values map, got ${annotation.values}")
                end match
        end match
    }

    "readAnnotations decodes @Foo(value={\"a\",\"b\"}) into ArrayVal(StringVal,StringVal)" in {
        val poolBytes = buildUtf8OnlyPool("LFoo;", "value", "a", "b")
        val poolView  = ByteView(poolBytes)
        ConstantPool.read(poolView, "<test>") match
            case Result.Failure(err) => fail(s"Pool read failed: $err")
            case Result.Panic(ex)    => fail(s"Pool read panicked: $ex")
            case Result.Success(pool) =>
                val annBytes = Array[Byte](
                    0x00,
                    0x01, // num_annotations = 1
                    0x00,
                    0x01, // type_index = 1
                    0x00,
                    0x01, // num_pairs = 1
                    0x00,
                    0x02,       // name_index = 2  ("value")
                    '['.toByte, // tag = array
                    0x00,
                    0x02, // num_values = 2
                    's'.toByte,
                    0x00,
                    0x03, // element 0: String at #3 ("a")
                    's'.toByte,
                    0x00,
                    0x04 // element 1: String at #4 ("b")
                )
                val annView = ByteView(annBytes)
                JavaAnnotationUnpickler.readAnnotations(annView, pool) match
                    case Result.Failure(err) => fail(s"readAnnotations failed: $err")
                    case Result.Panic(ex)    => fail(s"readAnnotations panicked: $ex")
                    case Result.Success(anns) =>
                        assert(anns.size == 1, s"Expected 1 annotation, got ${anns.size}")
                        val annotation = anns.head
                        val key        = Tasty.Name("value")
                        val valueOpt   = annotation.values.find(_._1 == key)
                        assert(valueOpt.isDefined, s"Expected key Name('value') in values: ${annotation.values}")
                        valueOpt.get._2 match
                            case Tasty.Java.Annotation.Value.ArrayVal(elems) =>
                                assert(elems.size == 2, s"Expected 2 array elements, got ${elems.size}")
                                elems(0) match
                                    case Tasty.Java.Annotation.Value.StringVal(s) =>
                                        assert(s == "a", s"Expected first element 'a', got '$s'")
                                    case other =>
                                        fail(s"Expected StringVal('a'), got $other")
                                end match
                                elems(1) match
                                    case Tasty.Java.Annotation.Value.StringVal(s) =>
                                        assert(s == "b", s"Expected second element 'b', got '$s'")
                                    case other =>
                                        fail(s"Expected StringVal('b'), got $other")
                                end match
                            case other =>
                                fail(s"Expected ArrayVal for key 'value', got $other")
                        end match
                end match
        end match
    }

end JavaAnnotationUnpicklerTest
