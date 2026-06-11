package kyo.internal

import kyo.*
import scala.collection.mutable.ArrayBuffer

private[kyo] object YamlEventScanner:

    def collect[Err](
        input: String,
        processor: Yaml.Events.Processor[Err]
    )(using Frame): Result[Err | DecodeException, Chunk[Yaml.Events.Event]] =
        val buffer    = ArrayBuffer.empty[Yaml.Events.Event]
        val collector = Collector[Err | DecodeException](buffer)
        YamlParser(input).visitEvents(())(processor.andThen(collector)).map(_ => Chunk.from(buffer))
    end collect

    final private class Collector[Err](
        buffer: ArrayBuffer[Yaml.Events.Event]
    ) extends Yaml.Events.Handler[Unit, Err]:

        override def streamStart(context: Unit, mark: Yaml.Mark): Result[Err, Unit] =
            buffer += Yaml.Events.Event.StreamStart(mark)
            Result.unit
        end streamStart

        override def documentStart(context: Unit, mark: Yaml.Mark): Result[Err, Unit] =
            buffer += Yaml.Events.Event.DocumentStart(mark)
            Result.unit
        end documentStart

        override def mappingStart(context: Unit, meta: Yaml.Meta, size: Maybe[Int]): Result[Err, Unit] =
            buffer += Yaml.Events.Event.MappingStart(meta, size)
            Result.unit
        end mappingStart

        override def sequenceStart(context: Unit, meta: Yaml.Meta, size: Maybe[Int]): Result[Err, Unit] =
            buffer += Yaml.Events.Event.SequenceStart(meta, size)
            Result.unit
        end sequenceStart

        override def scalar(context: Unit, value: String, meta: Yaml.ScalarMeta): Result[Err, Unit] =
            buffer += Yaml.Events.Event.Scalar(value, meta)
            Result.unit
        end scalar

        override def alias(context: Unit, name: Yaml.Anchor, mark: Yaml.Mark): Result[Err, Unit] =
            buffer += Yaml.Events.Event.Alias(name, mark)
            Result.unit
        end alias

        override def collectionEnd(context: Unit, kind: Yaml.Events.CollectionKind, mark: Yaml.Mark): Result[Err, Unit] =
            buffer += Yaml.Events.Event.CollectionEnd(kind, mark)
            Result.unit
        end collectionEnd

        override def documentEnd(context: Unit, mark: Yaml.Mark): Result[Err, Unit] =
            buffer += Yaml.Events.Event.DocumentEnd(mark)
            Result.unit
        end documentEnd

        override def streamEnd(context: Unit, mark: Yaml.Mark): Result[Err, Unit] =
            buffer += Yaml.Events.Event.StreamEnd(mark)
            Result.unit
        end streamEnd
    end Collector
end YamlEventScanner
