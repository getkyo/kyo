# kyo-kafka

`kyo-kafka` integrates Apache Kafka with Kyo on the JVM, wrapping the official `kafka-clients` library with idiomatic Kyo APIs. Producers and consumers are `Scope`-managed resources: their underlying clients are closed exactly once when the enclosing scope exits, whether that exit is normal, an error, or a cancellation. Sends are asynchronous and complete with the broker-acknowledged metadata; consumption is exposed both as one-shot `poll` batches and as a continuous Kyo `Stream`.

```scala
import kyo.*

val program: Chunk[KafkaRecord] < (Scope & Async & Sync & Abort[KafkaException]) =
    Scope.run {
        for
            producer <- KafkaProducer.init(KafkaConfig("localhost:9092"))
            consumer <- KafkaConsumer.init(
                KafkaConfig("localhost:9092").withProperty("group.id", "my-service"),
                Seq("events")
            )
            meta    <- producer.send(KafkaRecord.text("events", "hello"))
            records <- consumer.stream(500.millis).take(10).run
        yield records
    }
```

## Configuration

`KafkaConfig` holds the broker list plus any `org.apache.kafka.clients` property, with an immutable builder API. Keys follow the Kafka convention (`"security.protocol"`, `"sasl.jaas.config"`, ...). Serializers default to byte arrays; override them per client to use `StringSerializer` and friends.

```scala
val config = KafkaConfig("broker1:9092,broker2:9092")
    .withProperty("client.id", "my-service")
    .withProperty("security.protocol", "SASL_SSL")
```

## Producing

`KafkaProducer.init(config)` creates a producer bound to the current `Scope`. `send` suspends until the broker acknowledges the record (per the `acks` setting) and returns its `KafkaRecordMetadata`, aborting with `KafkaOperationException` on failure. `flush` blocks until all buffered records are sent.

```scala
Scope.run {
    for
        producer <- KafkaProducer.init(KafkaConfig("localhost:9092"))
        meta     <- producer.send(KafkaRecord.text("events", "hello", Maybe.Present("key-1")))
    yield meta.offset
}
```

## Consuming

`KafkaConsumer.init(config, topics)` subscribes and binds the client to the current `Scope`. `poll(timeout)` returns one batch of records; `stream(pollTimeout)` polls continuously and emits records as a `Stream`, so it composes with `take`, `map`, and the rest of the stream API. Auto-commit is disabled by default: commit explicitly with `commitSync` or `commitAsync`.

```scala
Scope.run {
    for
        consumer <- KafkaConsumer.init(KafkaConfig("localhost:9092"), Seq("events"))
        records  <- consumer.stream(500.millis).take(100).run
        _        <- consumer.commitSync
    yield records
}
```

## Errors

All operations abort with `Abort[KafkaException]`; `KafkaOperationException` carries the underlying Kafka client `Throwable` as its cause.
