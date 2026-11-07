package kyo

import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord, KafkaConsumer}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.{Deserializer, Serializer}
import java.time.Duration
import java.util.Properties
import scala.jdk.CollectionConverters.*

object Kafka:

    final case class ProducerSettings(
        bootstrapServers: String,
        properties: Map[String, String] = Map.empty
    )

    final case class ConsumerSettings(
        bootstrapServers: String,
        groupId: String,
        autoOffsetReset: String = "earliest",
        properties: Map[String, String] = Map.empty
    )

    final class Producer[K, V] private[kyo] (underlying: KafkaProducer[K, V]):
        def send(topic: String, key: K, value: V)(using Frame): RecordMetadata < (Async & Abort[Throwable]) =
            Async.fromFuture {
                val promise = scala.concurrent.Promise[RecordMetadata]()
                underlying.send(
                    new ProducerRecord[K, V](topic, key, value),
                    (metadata: RecordMetadata, exception: Exception) =>
                        if exception != null then promise.failure(exception)
                        else promise.success(metadata)
                )
                promise.future
            }

        def close()(using Frame): Unit < Sync =
            Sync.defer(underlying.close())
    end Producer

    final class Consumer[K, V] private[kyo] (
        underlying: KafkaConsumer[K, V],
        pollTimeout: Duration = Duration.ofMillis(250)
    ):
        def subscribe(topics: Seq[String])(using Frame): Unit < Sync =
            Sync.defer(underlying.subscribe(topics.asJava))

        def stream(using Tag[Emit[Chunk[ConsumerRecord[K, V]]]], Frame): Stream[ConsumerRecord[K, V], Async & Abort[Throwable]] =
            Stream {
                Loop(()) { _ =>
                    Abort.catching[Throwable] {
                        Sync.defer {
                            val records = underlying.poll(pollTimeout)
                            Chunk.from(records.asScala.toSeq)
                        }.map { chunk =>
                            if chunk.isEmpty then Loop.continue(())
                            else Emit.valueWith(chunk)(Loop.continue(()))
                        }
                    }
                }
            }

        def commitSync()(using Frame): Unit < (Sync & Abort[Throwable]) =
            Abort.catching[Throwable](Sync.defer(underlying.commitSync()))

        def close()(using Frame): Unit < Sync =
            Sync.defer(underlying.close())
    end Consumer

    def initProducer[K, V](
        settings: ProducerSettings,
        keySerializer: Serializer[K],
        valueSerializer: Serializer[V]
    )(using Frame): Producer[K, V] < (Scope & Sync) =
        Scope.acquireRelease(
            Sync.defer {
                val props = new Properties()
                props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.bootstrapServers)
                settings.properties.foreach((k, v) => props.put(k, v))
                new Producer(new KafkaProducer[K, V](props, keySerializer, valueSerializer))
            }
        )(_.close())

    def initConsumer[K, V](
        settings: ConsumerSettings,
        keyDeserializer: Deserializer[K],
        valueDeserializer: Deserializer[V]
    )(using Frame): Consumer[K, V] < (Scope & Sync) =
        Scope.acquireRelease(
            Sync.defer {
                val props = new Properties()
                props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.bootstrapServers)
                props.put(ConsumerConfig.GROUP_ID_CONFIG, settings.groupId)
                props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, settings.autoOffsetReset)
                settings.properties.foreach((k, v) => props.put(k, v))
                new Consumer(new KafkaConsumer[K, V](props, keyDeserializer, valueDeserializer))
            }
        )(_.close())
end Kafka
