package kyo.internal

import kyo.*
import kyo.Codec.Writer

final class YamlWriter private (private var config: Yaml.WriterConfig) extends Writer:

    private var inner = YamlEvents.Writer(config)

    def objectStart(name: String, size: Int): Unit =
        inner.objectStart(name, size)

    def objectEnd(): Unit =
        inner.objectEnd()

    def arrayStart(size: Int): Unit =
        inner.arrayStart(size)

    def arrayEnd(): Unit =
        inner.arrayEnd()

    def fieldBytes(nameBytes: Array[Byte], fieldId: Int): Unit =
        inner.fieldBytes(nameBytes, fieldId)

    override def field(name: String, fieldId: Int): Unit =
        inner.field(name, fieldId)

    def string(value: String): Unit =
        inner.string(value)

    def int(value: Int): Unit =
        inner.int(value)

    def long(value: Long): Unit =
        inner.long(value)

    def float(value: Float): Unit =
        inner.float(value)

    def double(value: Double): Unit =
        inner.double(value)

    def boolean(value: Boolean): Unit =
        inner.boolean(value)

    def short(value: Short): Unit =
        inner.short(value)

    def byte(value: Byte): Unit =
        inner.byte(value)

    def char(value: Char): Unit =
        inner.char(value)

    def nil(): Unit =
        inner.nil()

    def mapStart(size: Int): Unit =
        inner.mapStart(size)

    def mapEnd(): Unit =
        inner.mapEnd()

    def bytes(value: Span[Byte]): Unit =
        inner.bytes(value)

    def bigInt(value: BigInt): Unit =
        inner.bigInt(value)

    def bigDecimal(value: BigDecimal): Unit =
        inner.bigDecimal(value)

    def instant(value: java.time.Instant): Unit =
        inner.instant(value)

    def duration(value: java.time.Duration): Unit =
        inner.duration(value)

    def result(): Span[Byte] =
        val result = inner.result()
        release(result.size)
        result
    end result

    override def resultString: String =
        val result = inner.resultString
        release(result.length)
        result
    end resultString

    private def reset(config: Yaml.WriterConfig): Unit =
        this.config = config
        inner = YamlEvents.Writer(config)
    end reset

    private def release(outputSize: Int): Unit =
        if outputSize <= YamlWriter.MaxCachedOutputSize then YamlWriter.cache.set(this)
        else YamlWriter.cache.set(null)
    end release
end YamlWriter

object YamlWriter:
    final private val MaxCachedOutputSize = 262144
    private[internal] val cache           = new ThreadLocal[YamlWriter]

    def apply(): YamlWriter = apply(Yaml.WriterConfig.Default)

    def apply(config: Yaml.WriterConfig): YamlWriter =
        val cached = cache.get()
        if cached == null then new YamlWriter(config)
        else
            cache.set(null)
            cached.reset(config)
            cached
        end if
    end apply
end YamlWriter
