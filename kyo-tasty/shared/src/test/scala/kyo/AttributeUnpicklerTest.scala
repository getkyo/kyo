package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.AttributeUnpickler
import kyo.internal.tasty.reader.FileAttributes
import kyo.internal.tasty.reader.TastyFormat

class AttributeUnpicklerTest extends kyo.test.Test[Any]:

    // Test 12: a TASTy file with no Attributes section returns FileAttributes.default with all flags false.
    "absent Attributes section returns FileAttributes.default (all flags false)" in {
        // Empty view = no attributes section payload.
        val view   = ByteView(Array.empty[Byte])
        val result = Abort.run[TastyError](AttributeUnpickler.read(view, Array.empty))
        result.map { r =>
            r match
                case Result.Success(fa) =>
                    assert(fa == FileAttributes.default)
                    assert(!fa.explicitNulls)
                    assert(!fa.captureChecked)
                    assert(!fa.isJava)
                    assert(!fa.isOutline)
                    assert(!fa.scala2StandardLibrary)
                    assert(fa.sourceFile == Absent)
                case Result.Failure(e) =>
                    fail(s"Expected FileAttributes.default but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 13: a synthesized Attributes section with isJava = true is parsed correctly.
    "synthesized Attributes section with JAVAattr sets isJava=true" in {
        // JAVAattr = tag 5 (no payload)
        // EXPLICITNULLSattr = tag 2 (no payload)
        val attrs = Array[Byte](
            TastyFormat.JAVAattr.toByte,         // isJava = true
            TastyFormat.EXPLICITNULLSattr.toByte // explicitNulls = true
        )
        val view   = ByteView(attrs)
        val result = Abort.run[TastyError](AttributeUnpickler.read(view, Array.empty))
        result.map { r =>
            r match
                case Result.Success(fa) =>
                    assert(fa.isJava)
                    assert(fa.explicitNulls)
                    assert(!fa.captureChecked)
                    assert(!fa.isOutline)
                    assert(!fa.scala2StandardLibrary)
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 14: a synthesized Attributes section with explicitNulls = true sets that flag.
    "synthesized Attributes section with EXPLICITNULLSattr sets explicitNulls=true" in {
        val attrs = Array[Byte](TastyFormat.EXPLICITNULLSattr.toByte)
        val view  = ByteView(attrs)
        Abort.run[TastyError](AttributeUnpickler.read(view, Array.empty)).map { result =>
            result match
                case Result.Success(fa) =>
                    assert(fa.explicitNulls)
                    assert(!fa.isJava)
                    assert(!fa.captureChecked)
                    assert(!fa.scala2StandardLibrary)
                    assert(!fa.isOutline)
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 15: sourceFile attribute is decoded as Present("Foo.scala") when present, Absent otherwise.
    "SOURCEFILEattr decodes to Present(sourceFileName) when present" in {
        import AllowUnsafe.embrace.danger
        // Build a names array with "Foo.scala" at index 0.
        val nameStr                  = "Foo.scala"
        val names: Array[Tasty.Name] = Array(Tasty.Name(nameStr))
        // SOURCEFILEattr (0x81=129) + Utf8Ref=0 (encoded as single-byte NAT: 0 | 0x80 = 0x80)
        val attrs = Array[Byte](
            TastyFormat.SOURCEFILEattr.toByte, // 0x81
            (0 | 0x80).toByte                  // NAT 0 (0-based NameRef)
        )
        val view = ByteView(attrs)
        Abort.run[TastyError](AttributeUnpickler.read(view, names)).map { result =>
            result match
                case Result.Success(fa) =>
                    assert(fa.sourceFile == Present("Foo.scala"))
                    assert(!fa.explicitNulls)
                    assert(!fa.isJava)
                case Result.Failure(e) =>
                    fail(s"Expected Present(Foo.scala) but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

end AttributeUnpicklerTest
