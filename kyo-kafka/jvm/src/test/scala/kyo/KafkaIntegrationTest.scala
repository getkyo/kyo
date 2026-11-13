package kyo

import java.nio.charset.StandardCharsets

/** End-to-end tests against a single-node KRaft Kafka broker running in a container. */
class KafkaIntegrationTest extends Test:

    val hostPort = 19092

    def brokerConfig: Container.Config =
        Container.Config
            .default
            .copy(image = ContainerImage("apache/kafka", "3.9.1"))
            .env("KAFKA_NODE_ID", "1")
            .env("KAFKA_PROCESS_ROLES", "broker,controller")
            .env("KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093")
            .env("KAFKA_ADVERTISED_LISTENERS", s"PLAINTEXT://localhost:$hostPort")
            .env("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
            .env("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT")
            .env("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
            .env("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
            .env("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
            .env("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
            .env("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
            .env("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
            .env("CLUSTER_ID", "MkU3OEVBNTcwNTJENDM2Qg")
            .port(9092, hostPort)
            .requireService(true)
            .healthCheck(Container.HealthCheck.exec(
                Command("kafka-broker-api-versions.sh", "--bootstrap-server", "localhost:9092"),
                Maybe.Absent,
                Schedule.fixed(2.seconds).take(90)
            ))

    def bootstrap: String = s"localhost:$hostPort"

    def pollUntil(consumer: KafkaConsumer, topic: String, attempts: Int)(using Frame): Chunk[KafkaRecord] < (Sync & Abort[KafkaException]) =
        if attempts <= 0 then Chunk.empty
        else
            consumer.poll(5.seconds).flatMap { records =>
                val matching = records.filter(_.topic == topic)
                if matching.nonEmpty then matching
                else pollUntil(consumer, topic, attempts - 1)
            }

    "produce and consume a record" in {
        Container.initWith(brokerConfig) { _ =>
            Scope.run {
                for
                    producer <- KafkaProducer.init(KafkaConfig(bootstrap))
                    consumer <- KafkaConsumer.init(
                        KafkaConfig(bootstrap).withProperty("group.id", "kyo-it-basic"),
                        Seq("kyo-it-basic")
                    )
                    meta    <- producer.send(KafkaRecord.text("kyo-it-basic", "hello kafka"))
                    records <- pollUntil(consumer, "kyo-it-basic", 12)
                yield
                    assert(meta.topic == "kyo-it-basic")
                    assert(meta.offset == 0L)
                    assert(records.size == 1)
                    assert(new String(records.head.value, StandardCharsets.UTF_8) == "hello kafka")
            }
        }
    }

    "stream emits published records" in {
        Container.initWith(brokerConfig) { _ =>
            Scope.run {
                for
                    producer <- KafkaProducer.init(KafkaConfig(bootstrap))
                    consumer <- KafkaConsumer.init(
                        KafkaConfig(bootstrap).withProperty("group.id", "kyo-it-stream"),
                        Seq("kyo-it-stream")
                    )
                    _       <- producer.send(KafkaRecord.text("kyo-it-stream", "one"))
                    _       <- producer.send(KafkaRecord.text("kyo-it-stream", "two"))
                    records <- consumer.stream(1.second).take(2).run
                yield
                    assert(records.map(r => new String(r.value, StandardCharsets.UTF_8)) == Chunk("one", "two"))
            }
        }
    }

    "commitSync commits consumed offsets" in {
        Container.initWith(brokerConfig) { _ =>
            Scope.run {
                for
                    producer <- KafkaProducer.init(KafkaConfig(bootstrap))
                    consumer <- KafkaConsumer.init(
                        KafkaConfig(bootstrap).withProperty("group.id", "kyo-it-commit"),
                        Seq("kyo-it-commit")
                    )
                    _       <- producer.send(KafkaRecord.text("kyo-it-commit", "committed"))
                    records <- pollUntil(consumer, "kyo-it-commit", 12)
                    _       <- consumer.commitSync
                yield assert(records.nonEmpty)
            }
        }
    }
end KafkaIntegrationTest
