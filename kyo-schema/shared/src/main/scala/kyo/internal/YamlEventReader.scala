package kyo.internal

import kyo.*
import kyo.Codec.Reader

final private[kyo] class YamlEventReader private (private val inner: YamlReader)(using _frame: Frame) extends Reader:

    override def frame: Frame =
        _frame

    override private[kyo] def resetLimits(maxDepth: Int, maxCollectionSize: Int): Unit =
        super.resetLimits(maxDepth, maxCollectionSize)
        inner.resetLimits(maxDepth, maxCollectionSize)
    end resetLimits

    def objectStart(): Int =
        inner.objectStart()

    def objectEnd(): Unit =
        inner.objectEnd()

    def arrayStart(): Int =
        inner.arrayStart()

    def arrayEnd(): Unit =
        inner.arrayEnd()

    def field(): String =
        inner.field()

    override def fieldParse(): Unit =
        inner.fieldParse()

    override def matchField(nameBytes: Array[Byte]): Boolean =
        inner.matchField(nameBytes)

    override def lastFieldName(): String =
        inner.lastFieldName()

    def hasNextField(): Boolean =
        inner.hasNextField()

    def hasNextElement(): Boolean =
        inner.hasNextElement()

    def string(): String =
        inner.string()

    def int(): Int =
        inner.int()

    def long(): Long =
        inner.long()

    def float(): Float =
        inner.float()

    def double(): Double =
        inner.double()

    def boolean(): Boolean =
        inner.boolean()

    def short(): Short =
        inner.short()

    def byte(): Byte =
        inner.byte()

    def char(): Char =
        inner.char()

    def isNil(): Boolean =
        inner.isNil()

    def skip(): Unit =
        inner.skip()

    def mapStart(): Int =
        inner.mapStart()

    def mapEnd(): Unit =
        inner.mapEnd()

    def hasNextEntry(): Boolean =
        inner.hasNextEntry()

    def bytes(): Span[Byte] =
        inner.bytes()

    def bigInt(): BigInt =
        inner.bigInt()

    def bigDecimal(): BigDecimal =
        inner.bigDecimal()

    def instant(): java.time.Instant =
        inner.instant()

    def duration(): java.time.Duration =
        inner.duration()

    override def initFields(n: Int): Array[AnyRef] =
        inner.initFields(n)

    override def clearFields(n: Int): Unit =
        inner.clearFields(n)

    override def captureValue(): Reader =
        inner.captureValue()
end YamlEventReader

private[kyo] object YamlEventReader:

    def apply(events: Chunk[Yaml.Events.Event], yamlVersion: Yaml.SpecVersion)(using Frame): YamlEventReader =
        new YamlEventReader(YamlReader.fromEvents(events, yamlVersion))
    end apply

    def apply(events: Chunk[Yaml.Events.Event])(using Frame): YamlEventReader =
        apply(events, Yaml.SpecVersion.Yaml12)
    end apply
end YamlEventReader
