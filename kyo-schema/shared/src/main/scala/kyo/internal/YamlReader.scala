package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Codec.Reader

final class YamlReader(input: Span[Byte])(using _frame: Frame) extends Reader:
    private var prepared: Maybe[JsonReader] = Absent

    def frame: Frame = _frame

    override private[kyo] def resetLimits(maxDepth: Int, maxCollectionSize: Int): Unit =
        super.resetLimits(maxDepth, maxCollectionSize)
        prepared.foreach(_.resetLimits(maxDepth, maxCollectionSize))
    end resetLimits

    def objectStart(): Int                                   = inner.objectStart()
    def objectEnd(): Unit                                    = inner.objectEnd()
    def arrayStart(): Int                                    = inner.arrayStart()
    def arrayEnd(): Unit                                     = inner.arrayEnd()
    def field(): String                                      = inner.field()
    def hasNextField(): Boolean                              = inner.hasNextField()
    def hasNextElement(): Boolean                            = inner.hasNextElement()
    def string(): String                                     = inner.string()
    def int(): Int                                           = inner.int()
    def long(): Long                                         = inner.long()
    def float(): Float                                       = inner.float()
    def double(): Double                                     = inner.double()
    def boolean(): Boolean                                   = inner.boolean()
    def short(): Short                                       = inner.short()
    def byte(): Byte                                         = inner.byte()
    def char(): Char                                         = inner.char()
    def isNil(): Boolean                                     = inner.isNil()
    def skip(): Unit                                         = inner.skip()
    def mapStart(): Int                                      = inner.mapStart()
    def mapEnd(): Unit                                       = inner.mapEnd()
    def hasNextEntry(): Boolean                              = inner.hasNextEntry()
    def bytes(): Span[Byte]                                  = inner.bytes()
    def bigInt(): BigInt                                     = inner.bigInt()
    def bigDecimal(): BigDecimal                             = inner.bigDecimal()
    def instant(): java.time.Instant                         = inner.instant()
    def duration(): java.time.Duration                       = inner.duration()
    override def fieldParse(): Unit                          = inner.fieldParse()
    override def matchField(nameBytes: Array[Byte]): Boolean = inner.matchField(nameBytes)
    override def lastFieldName(): String                     = inner.lastFieldName()
    override def initFields(n: Int): Array[AnyRef]           = inner.initFields(n)
    override def clearFields(n: Int): Unit                   = inner.clearFields(n)
    override def droppedFieldsMask(n: Int): Long             = inner.droppedFieldsMask(n)
    override def release(): Unit                             = prepared.foreach(_.release())
    override def captureValue(): Reader                      = inner.captureValue()

    private def inner: JsonReader =
        prepared match
            case Present(reader) => reader
            case Absent =>
                val json   = YamlParser.toJson(input, maxDepth, maxCollectionSize)(using _frame)
                val reader = JsonReader(json)
                reader.resetLimits(maxDepth, maxCollectionSize)
                prepared = Maybe(reader)
                reader
        end match
    end inner
end YamlReader

object YamlReader:
    def apply(input: Span[Byte])(using Frame): YamlReader = new YamlReader(input)
    def apply(input: String)(using Frame): YamlReader =
        apply(Span.from(input.getBytes(StandardCharsets.UTF_8)))
end YamlReader
