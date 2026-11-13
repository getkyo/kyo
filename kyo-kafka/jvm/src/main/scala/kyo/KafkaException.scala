package kyo

/** Base class for all kyo-kafka errors.
  *
  * Producer and consumer operations abort with `Abort[KafkaException]`, carrying the broker's or client's own failure (a
  * `org.apache.kafka.common.KafkaException` and friends) as the cause when one exists.
  *
  * @see [[KafkaProducer]] operations that abort with `KafkaException`
  * @see [[KafkaConsumer]] operations that abort with `KafkaException`
  */
sealed abstract class KafkaException(message: String, cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause)

/** A Kafka client operation failed. Wraps the underlying `Throwable` reported by the Kafka client (send callback error,
  * poll failure, commit failure, or a lifecycle error while opening or closing a client).
  */
final case class KafkaOperationException(message: String, cause: Throwable)(using Frame)
    extends KafkaException(message, cause)
