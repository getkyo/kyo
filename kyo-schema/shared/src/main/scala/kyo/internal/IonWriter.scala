package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.Codec.Writer
import kyo.Span

final class IonWriter private (private val out: StringBuilder) extends Writer:

    private enum Context:
        case Obj(first: Boolean)
        case Arr(first: Boolean)

    import Context.*

    private var stack: List[Context] = Nil
    private var afterField: Boolean  = false

    // Reusable scratch buffer for Ryu float formatting (sized to RyuDouble.MaxOutputLen).
    private val numberBytes = new Array[Byte](24)

    def objectStart(name: String, size: Int): Unit =
        beforeValue()
        out.append('{')
        stack = Obj(true) :: stack
    end objectStart

    def objectEnd(): Unit =
        stack match
            case Obj(_) :: tail =>
                stack = tail
                out.append('}')
            case other =>
                throw new IllegalStateException(s"IonWriter.objectEnd without object context: $other")
        end match
    end objectEnd

    def arrayStart(size: Int): Unit =
        beforeValue()
        out.append('[')
        stack = Arr(true) :: stack
    end arrayStart

    def arrayEnd(): Unit =
        stack match
            case Arr(_) :: tail =>
                stack = tail
                out.append(']')
            case other =>
                throw new IllegalStateException(s"IonWriter.arrayEnd without array context: $other")
        end match
    end arrayEnd

    def fieldBytes(nameBytes: Array[Byte], fieldId: Int): Unit =
        field(new String(nameBytes, StandardCharsets.UTF_8), fieldId)

    override def field(name: String, fieldId: Int): Unit =
        stack match
            case Obj(first) :: tail =>
                if !first then out.append(',')
                stack = Obj(false) :: tail
                writeFieldName(name)
                out.append(':')
                afterField = true
            case other =>
                throw new IllegalStateException(s"IonWriter.field without object context: $other")
        end match
    end field

    def string(value: String): Unit =
        beforeValue()
        writeQuotedString(value)

    def int(value: Int): Unit =
        beforeValue()
        out.append(value)

    def long(value: Long): Unit =
        beforeValue()
        out.append(value)

    def float(value: Float): Unit =
        beforeValue()
        writeFloat(value.toDouble)

    def double(value: Double): Unit =
        beforeValue()
        writeFloat(value)

    def boolean(value: Boolean): Unit =
        beforeValue()
        out.append(if value then "true" else "false")

    def short(value: Short): Unit =
        beforeValue()
        out.append(value.toInt)

    def byte(value: Byte): Unit =
        beforeValue()
        out.append(value.toInt)

    def char(value: Char): Unit =
        beforeValue()
        writeQuotedString(value.toString)

    def nil(): Unit =
        beforeValue()
        out.append("null")

    def mapStart(size: Int): Unit = objectStart("", size)
    def mapEnd(): Unit            = objectEnd()

    def bytes(value: Span[Byte]): Unit =
        beforeValue()
        out.append("{{")
        out.append(java.util.Base64.getEncoder.encodeToString(value.toArray))
        out.append("}}")
    end bytes

    def bigInt(value: BigInt): Unit =
        beforeValue()
        out.append(value.toString)

    def bigDecimal(value: BigDecimal): Unit =
        beforeValue()
        out.append(value.toString.replace('E', 'd').replace('e', 'd'))

    def instant(value: java.time.Instant): Unit =
        beforeValue()
        out.append(value.toString)

    def duration(value: java.time.Duration): Unit =
        beforeValue()
        writeQuotedString(value.toString)

    def result(): Span[Byte] =
        Span.from(out.toString.getBytes(StandardCharsets.UTF_8))

    override def resultString: String = out.toString

    private def beforeValue(): Unit =
        if afterField then
            afterField = false
        else
            stack match
                case Arr(first) :: tail =>
                    if !first then out.append(',')
                    stack = Arr(false) :: tail
                case _ => ()
            end match
    end beforeValue

    private def writeFloat(value: Double): Unit =
        if java.lang.Double.isNaN(value) then out.append("nan")
        else if value == Double.PositiveInfinity then out.append("+inf")
        else if value == Double.NegativeInfinity then out.append("-inf")
        else
            // Ryu yields Java's Double.toString format deterministically across JVM, JS, and Native,
            // unlike java.lang.Double.toString which diverges (for example 5.0 renders "5" on Scala.js).
            val end  = Ryu.RyuDouble.write(value, numberBytes, 0, numberBytes.length)
            val text = new String(numberBytes, 0, end, StandardCharsets.UTF_8)
            out.append(text)
            if text.indexOf('E') < 0 && text.indexOf('e') < 0 then out.append("e0")
        end if
    end writeFloat

    private def writeFieldName(value: String): Unit =
        if isIdentifier(value) && !IonWriter.ReservedSymbols.contains(value) then out.append(value)
        else writeQuotedString(value)

    private def isIdentifier(value: String): Boolean =
        if value.isEmpty then false
        else
            val first = value.charAt(0)
            if !isIdentifierStart(first) then false
            else
                var i  = 1
                var ok = true
                while ok && i < value.length do
                    ok = isIdentifierPart(value.charAt(i))
                    i += 1
                ok
            end if
        end if
    end isIdentifier

    private def isIdentifierStart(c: Char): Boolean =
        (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$'

    private def isIdentifierPart(c: Char): Boolean =
        isIdentifierStart(c) || (c >= '0' && c <= '9')

    private def writeQuotedString(value: String): Unit =
        out.append('"')
        var i = 0
        while i < value.length do
            val c = value.charAt(i)
            c match
                case '"'  => out.append("\\\"")
                case '\\' => out.append("\\\\")
                case '\b' => out.append("\\b")
                case '\f' => out.append("\\f")
                case '\n' => out.append("\\n")
                case '\r' => out.append("\\r")
                case '\t' => out.append("\\t")
                case _ =>
                    if c < 0x20 then
                        out.append("\\u")
                        val hex = Integer.toHexString(c.toInt)
                        var pad = hex.length
                        while pad < 4 do
                            out.append('0')
                            pad += 1
                        out.append(hex)
                    else out.append(c)
            end match
            i += 1
        end while
        out.append('"')
    end writeQuotedString

end IonWriter

object IonWriter:
    private val ReservedSymbols: Set[String] =
        Set("null", "true", "false", "nan", "+inf", "-inf")

    def apply(): IonWriter = new IonWriter(new StringBuilder(256))
end IonWriter
