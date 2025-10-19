package kyo.experimental

import kyo.*

class HKTMapTest extends Test:

    trait Repository[-S]:
        def save(item: String): Unit < S
        def find: String < S

    trait Cache[-S]:
        def put(key: String, value: String): Unit < S
        def get(key: String): Option[String] < S

    trait Logger[-S]:
        def log(level: String, message: String): Unit < S

    // Concrete implementations that work with specific effect types
    object InMemoryRepository extends Repository[Any]:
        def save(item: String): Unit < Any = ()
        def find: String < Any             = "found-item"

    object SimpleCache extends Cache[Any]:
        def put(key: String, value: String): Unit < Any = ()
        def get(key: String): Option[String] < Any      = Some(s"cached-$key")

    object ConsoleLogger extends Logger[Any]:
        def log(level: String, message: String): Unit < Any =
            println(s"[$level] $message")

    "HKTMap with Kyo effects" - {
        "empty" - {
            "should create empty map" in {
                val empty = HKTMap.empty
                assert(empty.isEmpty)
                assert(empty.size == 0)
            }
        }

        "basic operations with effect-based services" - {
            "should create map with single service" in run {
                // Create HKTMap with Repository service
                val map: HKTMap[Repository, Any] = HKTMap[Repository, Any](InMemoryRepository)

                assert(map.size == 1)
                val repo = map.get[Repository]

                // Use the repository - effects are handled by the test framework
                repo.save("test-item").map { _ =>
                    repo.find.map { result =>
                        assert(result == "found-item")
                    }
                }
            }

            "should work with multiple services" in run {
                val repoMap: HKTMap[Repository, Any] = HKTMap[Repository, Any](InMemoryRepository)
                val cacheMap: HKTMap[Cache, Any]     = HKTMap[Cache, Any](SimpleCache)

                val combined = repoMap.union(cacheMap)
                assert(combined.size == 2)

                val repo  = combined.get[Repository]
                val cache = combined.get[Cache]

                // Test both services work
                repo.find.map { repoResult =>
                    cache.get("test-key").map { cacheResult =>
                        assert(repoResult == "found-item")
                        assert(cacheResult.contains("cached-test-key"))
                    }
                }
            }
        }

        "union operations with effectful services" - {
            "should combine compatible service maps" in run {
                val repoMap: HKTMap[Repository, Any] = HKTMap[Repository, Any](InMemoryRepository)
                val loggerMap: HKTMap[Logger, Any]   = HKTMap[Logger, Any](ConsoleLogger)

                val combined = repoMap.union(loggerMap)
                assert(combined.size == 2)

                val repo   = combined.get[Repository]
                val logger = combined.get[Logger]

                // Test service composition
                logger.log("INFO", "Starting operation").map { _ =>
                    repo.save("important-data").map { _ =>
                        logger.log("INFO", "Operation completed").map { _ =>
                            succeed
                        }
                    }
                }
            }

            "should handle service replacement" in run {
                object AlternativeRepository extends Repository[Any]:
                    def save(item: String): Unit < Any = ()
                    def find: String < Any             = "alternative-result"

                val map1: HKTMap[Repository, Any] = HKTMap[Repository, Any](InMemoryRepository)
                val map2: HKTMap[Repository, Any] = HKTMap[Repository, Any](AlternativeRepository)

                val combined = map1.union(map2)
                assert(combined.size == 1)

                val repo = combined.get[Repository]

                repo.find.map { result =>
                    assert(result == "alternative-result") // Second one wins
                }
            }
        }

        "constructor methods with services" - {
            "should create map with multiple services directly" in run {
                val map = HKTMap(InMemoryRepository, SimpleCache, ConsoleLogger)

                assert(map.size == 3)

                val repo   = map.get[Repository]
                val cache  = map.get[Cache]
                val logger = map.get[Logger]

                // Test all services in a composition
                logger.log("DEBUG", "Testing services").map { _ =>
                    cache.put("test", "value").map { _ =>
                        repo.save("test-data").map { _ =>
                            cache.get("test").map { cached =>
                                repo.find.map { found =>
                                    assert(cached.contains("cached-test"))
                                    assert(found == "found-item")
                                }
                            }
                        }
                    }
                }
            }
        }

        "show and debugging" - {
            "should provide readable string representation" in {
                val map   = HKTMap(InMemoryRepository, SimpleCache)
                val shown = map.show
                assert(shown.contains("HKTMap"))
                assert(shown.length > 10) // Should contain meaningful content
            }
        }

        "type safety with effects" - {
            "should maintain effect type information" in run {
                val map: HKTMap[Repository, Any] = HKTMap[Repository, Any](InMemoryRepository)

                // The retrieved repository should maintain its effect type
                val repo: Repository[Any] = map.get[Repository]

                // Should be able to use the repository with proper effects
                repo.save("typed-data").map { _ =>
                    repo.find.map { result =>
                        assert(result == "found-item")
                        assert(result.isInstanceOf[String])
                    }
                }
            }
        }

        "advanced effect types" - {
            "should work with Abort effects" in run {
                // Define services that can fail with Abort
                trait FailableRepository[-S]:
                    def save(item: String): Unit < (S & Abort[String])
                    def find(id: String): String < (S & Abort[String])

                object ValidatingRepository extends FailableRepository[Any]:
                    def save(item: String): Unit < Abort[String] =
                        if item.nonEmpty then ()
                        else Abort.fail("Item cannot be empty")

                    def find(id: String): String < Abort[String] =
                        if id == "valid" then s"found-$id"
                        else Abort.fail("Item not found")
                end ValidatingRepository

                val map: HKTMap[FailableRepository, Abort[String]] =
                    HKTMap[FailableRepository, Abort[String]](ValidatingRepository)

                val repo = map.get[FailableRepository]

                // Test successful case
                Abort.run {
                    repo.save("valid-item").map { _ =>
                        repo.find("valid").map { result =>
                            assert(result == "found-valid")
                        }
                    }
                }.map { result =>
                    assert(result.isSuccess)
                }
            }

            "should work with Emit effects" in run {
                // Define services that emit events
                trait EventEmittingService[-S]:
                    def processWithEvents(data: String): String < (S & Emit[String])

                object AuditingService extends EventEmittingService[Any]:
                    def processWithEvents(data: String): String < Emit[String] =
                        Emit.value("Processing started").map { _ =>
                            Emit.value(s"Processing data: $data").map { _ =>
                                Emit.value("Processing completed").map { _ =>
                                    s"processed-$data"
                                }
                            }
                        }
                end AuditingService

                val map: HKTMap[EventEmittingService, Emit[String]] =
                    HKTMap[EventEmittingService, Emit[String]](AuditingService)

                val service = map.get[EventEmittingService]

                // Test emit collection
                Emit.run {
                    service.processWithEvents("test-data")
                }.map { (events, result) =>
                    assert(result == "processed-test-data")
                    assert(events.size == 3)
                    assert(events.contains("Processing started"))
                    assert(events.contains("Processing data: test-data"))
                    assert(events.contains("Processing completed"))
                }
            }

            "should work with combined effect types" in run {
                // Service that uses multiple effects
                trait ComplexService[-S]:
                    def complexOperation(input: String): String < (S & Abort[String] & Emit[String])

                object BusinessService extends ComplexService[Any]:
                    def complexOperation(input: String): String < (Abort[String] & Emit[String]) =
                        Emit.value(s"Starting operation with: $input").map { _ =>
                            if input.isEmpty then Abort.fail("Input cannot be empty")
                            else
                                Emit.value("Validation passed").map { _ =>
                                    Emit.value("Operation completed").map { _ =>
                                        s"result-$input"
                                    }
                                }
                        }
                end BusinessService

                val map: HKTMap[ComplexService, Abort[String] & Emit[String]] =
                    HKTMap[ComplexService, Abort[String] & Emit[String]](BusinessService)

                val service = map.get[ComplexService](using
                    Tag[ComplexService[Any]],
                    summon[ComplexService[Any] <:< ComplexService[Any]]
                )

                // Test successful case with both effects
                Emit.run {
                    Abort.run {
                        service.complexOperation("valid-input")
                    }
                }.map { (events, abortResult) =>
                    assert(abortResult.isSuccess)
                    abortResult match
                        case Result.Success(value) => assert(value == "result-valid-input")
                        case Result.Failure(_)     => fail("Expected success")
                    assert(events.size == 3)
                    assert(events.contains("Starting operation with: valid-input"))
                    assert(events.contains("Validation passed"))
                    assert(events.contains("Operation completed"))
                }
            }

            "should handle service composition with different effects" in run {
                // Different services with different effect requirements
                trait DatabaseService[-S]:
                    def query(sql: String): List[String] < S

                trait MetricsService[-S]:
                    def recordMetric(name: String, value: Double): Unit < S

                object PostgresService extends DatabaseService[Abort[String]]:
                    def query(sql: String): List[String] < Abort[String] =
                        if sql.contains("DROP") then Abort.fail("Dangerous query detected")
                        else List(s"result-$sql")
                end PostgresService

                object PrometheusService extends MetricsService[Emit[String]]:
                    def recordMetric(name: String, value: Double): Unit < Emit[String] =
                        Emit.value(s"Metric recorded: $name = $value")

                // Create separate maps for different effect types
                val dbMap: HKTMap[DatabaseService, Abort[String]]    = HKTMap[DatabaseService, Abort[String]](PostgresService)
                val metricsMap: HKTMap[MetricsService, Emit[String]] = HKTMap[MetricsService, Emit[String]](PrometheusService)

                val all: HKTMap[DatabaseService && MetricsService, Abort[String] & Emit[String]] = dbMap.union(metricsMap)

                // Services can be used independently with their respective effects
                val db      = all.get[DatabaseService]
                val metrics = all.get[MetricsService]

                // Compose a program using both db and metrics, then handle effects and test after
                val program: List[String] < (Abort[String] & Emit[String]) =
                    for
                        result <- db.query("SELECT * FROM users")
                        _      <- metrics.recordMetric("query_count", 1.0)
                    yield result

                val (events, dbResult) = program.handle(Abort.run(_), Emit.run(_), _.eval)

                assert(dbResult.isSuccess)
                dbResult match
                    case Result.Success(value) => assert(value == List("result-SELECT * FROM users"))
                    case Result.Failure(_)     => fail("Expected success")
                assert(events.contains("Metric recorded: query_count = 1.0"))

            }
        }
    }

end HKTMapTest
