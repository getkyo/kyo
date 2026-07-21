package kyo.internal

import kyo.Codec
import kyo.Frame
import kyo.Span
import kyo.Test
import kyo.internal.postgres.types.Format

/** Compile-time contract tests for [[SqlWriter]].
  *
  * Confirms that SqlWriter satisfies the Codec.Writer supertype contract and exposes the custom escape method with the expected signature.
  * No runtime assertions are needed beyond what the compiler checks.
  */
class SqlWriterTest extends Test:

    "SqlWriter is-a Codec.Writer" in {
        // Instantiate an anonymous subclass to verify SqlWriter can be assigned to Codec.Writer.
        // The compiler rejects this if SqlWriter does not extend Codec.Writer.
        val writer: Codec.Writer = new SqlWriter(summon[Frame]):
            def objectStart(name: String, size: Int): Unit                        = ()
            def objectEnd(): Unit                                                 = ()
            def arrayStart(size: Int): Unit                                       = ()
            def arrayEnd(): Unit                                                  = ()
            def fieldBytes(nameBytes: Array[Byte], fieldId: Int): Unit            = ()
            def string(value: String): Unit                                       = ()
            def int(value: Int): Unit                                             = ()
            def long(value: Long): Unit                                           = ()
            def float(value: Float): Unit                                         = ()
            def double(value: Double): Unit                                       = ()
            def boolean(value: Boolean): Unit                                     = ()
            def short(value: Short): Unit                                         = ()
            def byte(value: Byte): Unit                                           = ()
            def char(value: Char): Unit                                           = ()
            def nil(): Unit                                                       = ()
            def mapStart(size: Int): Unit                                         = ()
            def mapEnd(): Unit                                                    = ()
            def bytes(value: Span[Byte]): Unit                                    = ()
            def bigInt(value: BigInt): Unit                                       = ()
            def bigDecimal(value: BigDecimal): Unit                               = ()
            def instant(value: java.time.Instant): Unit                           = ()
            def duration(value: java.time.Duration): Unit                         = ()
            def result(): Span[Byte]                                              = Span.empty
            def custom(typeName: String, bytes: Span[Byte], format: Format): Unit = ()
        assert(writer.isInstanceOf[Codec.Writer])
    }

    "SqlWriter exposes frame val from constructor" in {
        // Confirms that the Frame passed at construction is accessible via the frame member.
        val capturedFrame = summon[Frame]
        val sqlWriter = new SqlWriter(capturedFrame):
            def objectStart(name: String, size: Int): Unit                        = ()
            def objectEnd(): Unit                                                 = ()
            def arrayStart(size: Int): Unit                                       = ()
            def arrayEnd(): Unit                                                  = ()
            def fieldBytes(nameBytes: Array[Byte], fieldId: Int): Unit            = ()
            def string(value: String): Unit                                       = ()
            def int(value: Int): Unit                                             = ()
            def long(value: Long): Unit                                           = ()
            def float(value: Float): Unit                                         = ()
            def double(value: Double): Unit                                       = ()
            def boolean(value: Boolean): Unit                                     = ()
            def short(value: Short): Unit                                         = ()
            def byte(value: Byte): Unit                                           = ()
            def char(value: Char): Unit                                           = ()
            def nil(): Unit                                                       = ()
            def mapStart(size: Int): Unit                                         = ()
            def mapEnd(): Unit                                                    = ()
            def bytes(value: Span[Byte]): Unit                                    = ()
            def bigInt(value: BigInt): Unit                                       = ()
            def bigDecimal(value: BigDecimal): Unit                               = ()
            def instant(value: java.time.Instant): Unit                           = ()
            def duration(value: java.time.Duration): Unit                         = ()
            def result(): Span[Byte]                                              = Span.empty
            def custom(typeName: String, bytes: Span[Byte], format: Format): Unit = ()
        assert(sqlWriter.frame eq capturedFrame)
    }

    "SqlWriter exposes custom method with correct signature" in {
        // Invoke the custom method to confirm it exists with the expected parameter types.
        // java.util.concurrent.atomic.AtomicBoolean is required here: `custom(...)` is a plain
        // synchronous callback (non-Kyo context), so kyo.AtomicBoolean (which returns Boolean < Sync)
        // cannot be used inside it.
        val customCalled = new java.util.concurrent.atomic.AtomicBoolean(false)
        val sqlWriter = new SqlWriter(summon[Frame]):
            def objectStart(name: String, size: Int): Unit             = ()
            def objectEnd(): Unit                                      = ()
            def arrayStart(size: Int): Unit                            = ()
            def arrayEnd(): Unit                                       = ()
            def fieldBytes(nameBytes: Array[Byte], fieldId: Int): Unit = ()
            def string(value: String): Unit                            = ()
            def int(value: Int): Unit                                  = ()
            def long(value: Long): Unit                                = ()
            def float(value: Float): Unit                              = ()
            def double(value: Double): Unit                            = ()
            def boolean(value: Boolean): Unit                          = ()
            def short(value: Short): Unit                              = ()
            def byte(value: Byte): Unit                                = ()
            def char(value: Char): Unit                                = ()
            def nil(): Unit                                            = ()
            def mapStart(size: Int): Unit                              = ()
            def mapEnd(): Unit                                         = ()
            def bytes(value: Span[Byte]): Unit                         = ()
            def bigInt(value: BigInt): Unit                            = ()
            def bigDecimal(value: BigDecimal): Unit                    = ()
            def instant(value: java.time.Instant): Unit                = ()
            def duration(value: java.time.Duration): Unit              = ()
            def result(): Span[Byte]                                   = Span.empty
            def custom(typeName: String, bytes: Span[Byte], format: Format): Unit =
                customCalled.set(true)
        sqlWriter.custom("geometry", Span.empty, Format.Binary)
        assert(customCalled.get())
    }

end SqlWriterTest
