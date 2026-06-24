package externalcodec

import java.time.Duration
import java.time.Instant
import kyo.Codec.Writer
import kyo.Span

// A Writer defined OUTSIDE the kyo package. It can only see the public surface of Codec.Writer,
// so this file compiles only while the representation-capability hooks remain overridable by
// external codecs. If canWriteTopLevelNonObject or codecName were narrowed to private[kyo], the
// overrides below would not compile, and this test would fail at build time.
final class ExternalWriter extends Writer:
    override def canWriteTopLevelNonObject: Boolean = true
    override def codecName: String                  = "custom-codec"

    override def objectStart(name: String, size: Int): Unit             = ()
    override def objectEnd(): Unit                                      = ()
    override def arrayStart(size: Int): Unit                            = ()
    override def arrayEnd(): Unit                                       = ()
    override def fieldBytes(nameBytes: Array[Byte], fieldId: Int): Unit = ()
    override def string(value: String): Unit                            = ()
    override def int(value: Int): Unit                                  = ()
    override def long(value: Long): Unit                                = ()
    override def float(value: Float): Unit                              = ()
    override def double(value: Double): Unit                            = ()
    override def boolean(value: Boolean): Unit                          = ()
    override def short(value: Short): Unit                              = ()
    override def byte(value: Byte): Unit                                = ()
    override def char(value: Char): Unit                                = ()
    override def nil(): Unit                                            = ()
    override def mapStart(size: Int): Unit                              = ()
    override def mapEnd(): Unit                                         = ()
    override def bytes(value: Span[Byte]): Unit                         = ()
    override def bigInt(value: BigInt): Unit                            = ()
    override def bigDecimal(value: BigDecimal): Unit                    = ()
    override def instant(value: Instant): Unit                          = ()
    override def duration(value: Duration): Unit                        = ()
    override def result(): Span[Byte]                                   = Span.empty[Byte]
end ExternalWriter

class CodecExtensionTest extends kyo.test.Test[Any]:

    "an external codec can declare the top-level-non-object capability" in {
        val writer = ExternalWriter()
        // The capability hook is overridable outside the kyo package, so a custom format that can carry
        // a top-level array, scalar, or null can opt into the Tuple, TupleFlat, and Untagged representations.
        assert(writer.canWriteTopLevelNonObject)
    }

    "an external codec supplies its own user-facing codec name" in {
        val writer = ExternalWriter()
        // codecName is overridable outside the kyo package, so a custom codec's error messages name the
        // codec the user selected rather than the writer's class name.
        assert(writer.codecName == "custom-codec")
    }
end CodecExtensionTest
