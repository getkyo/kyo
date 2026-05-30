package kyo

final case class YamlGithubWorkflow(name: String, `on`: YamlGithubOn, jobs: Map[String, YamlGithubJob]) derives CanEqual
final case class YamlGithubOn(push: YamlGithubPush, pull_request: Option[String]) derives CanEqual
final case class YamlGithubPush(branches: List[String]) derives CanEqual
final case class YamlGithubJob(`runs-on`: String, steps: List[YamlGithubStep]) derives CanEqual
final case class YamlGithubStep(name: Option[String], uses: Option[String], run: Option[String], `with`: Option[Map[String, String]])
    derives CanEqual
final case class YamlOpenApiSpec(
    openapi: String,
    info: YamlOpenApiInfo,
    paths: Map[String, YamlOpenApiPathItem],
    components: YamlOpenApiComponents
) derives CanEqual
final case class YamlOpenApiInfo(title: String, version: String, description: String) derives CanEqual
final case class YamlOpenApiPathItem(get: YamlOpenApiOperation) derives CanEqual
final case class YamlOpenApiOperation(
    summary: String,
    operationId: String,
    parameters: List[YamlOpenApiParameter],
    responses: Map[String, YamlOpenApiResponse]
) derives CanEqual
final case class YamlOpenApiParameter(name: String, `in`: String, required: Boolean, schema: YamlOpenApiSchema) derives CanEqual
final case class YamlOpenApiSchema(`type`: String, format: Option[String]) derives CanEqual
final case class YamlOpenApiResponse(description: String) derives CanEqual
final case class YamlOpenApiComponents(schemas: Map[String, YamlOpenApiSchema]) derives CanEqual
final case class YamlDockerCompose(
    version: String,
    `x-logging`: YamlComposeLogging,
    services: Map[String, YamlComposeService],
    volumes: Map[String, YamlComposeVolume],
    networks: Map[String, YamlComposeNetwork]
) derives CanEqual
final case class YamlComposeService(
    image: String,
    ports: Option[List[String]],
    environment: Option[Map[String, String]],
    depends_on: Option[List[String]],
    volumes: Option[List[String]],
    networks: Option[List[String]],
    healthcheck: Option[YamlComposeHealthcheck],
    logging: Option[YamlComposeLogging],
    restart: Option[String]
) derives CanEqual
final case class YamlComposeHealthcheck(test: List[String], interval: String, timeout: String, retries: Int) derives CanEqual
final case class YamlComposeLogging(driver: String, options: Map[String, String]) derives CanEqual
final case class YamlComposeVolume(driver: Option[String]) derives CanEqual
final case class YamlComposeNetwork(driver: Option[String]) derives CanEqual
final case class YamlCoreScalars(
    norway: String,
    on: String,
    off: String,
    yes: String,
    no: String,
    trueValue: Boolean,
    falseValue: Boolean,
    nullValue: Option[String]
) derives CanEqual
final case class YamlCoreNumbers(
    octal: Int,
    hex: Int,
    leadingDot: Double,
    positiveExponent: Double,
    positiveInfinity: Double,
    negativeInfinity: Double,
    nan: Double
) derives CanEqual
final case class YamlLegacyBooleans(
    norway: Boolean,
    on: Boolean,
    off: Boolean,
    yes: Boolean,
    no: Boolean,
    y: Boolean,
    n: Boolean
) derives CanEqual
final case class YamlLegacyNumbers(
    octal: Int,
    binary: Int,
    sexagesimal: Int,
    underscored: Int,
    fixed: Double,
    sexagesimalFloat: Double
) derives CanEqual
final case class YamlLegacyTaggedScalars(intValue: Int, boolValue: Boolean, floatValue: Double) derives CanEqual
final case class YamlTaggedScalars(
    stringValue: String,
    intValue: Int,
    boolValue: Boolean,
    floatValue: Double,
    nullValue: Option[String],
    disabled: String
) derives CanEqual
final case class YamlPlayer(name: String, hr: Int, avg: Double) derives CanEqual
final case class YamlLeagues(american: List[String], national: List[String]) derives CanEqual
final case class YamlEscapes(unicode: String, control: String, hex: String) derives CanEqual
final case class YamlFlowText(doubleQuoted: String, plain: String) derives CanEqual
final case class YamlFlowScanDoc(items: List[YamlFlowScanItem]) derives CanEqual
final case class YamlFlowScanItem(name: String, url: String, labels: List[String]) derives CanEqual
final case class YamlMultilineScalars(
    literalStrip: String,
    literalClip: String,
    literalKeep: String,
    literalIndent: String,
    foldedStrip: String,
    foldedKeep: String,
    singleQuoted: String,
    doubleQuoted: String,
    plain: String
) derives CanEqual
final case class YamlHellConfig(server_config: YamlHellServerConfig) derives CanEqual
final case class YamlHellServerConfig(
    port_mapping: List[String],
    serve: List[String],
    geoblock_regions: List[String],
    flush_cache: YamlHellFlushCache
) derives CanEqual
final case class YamlHellFlushCache(on: List[String], priority: String) derives CanEqual

class YamlParserTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    "limits" - {

        "schema decode rejects nesting beyond maxDepth without JSON bridging" in {
            val yaml =
                """root:
                  |  child:
                  |    name: Alice
                  |""".stripMargin

            Yaml.decode[Map[String, Map[String, MTPerson]]](yaml, 1, Yaml.DefaultMaxCollectionSize) match
                case Result.Failure(e: LimitExceededException) =>
                    assert(e.limit == "Nesting depth")
                case other => fail(s"Expected LimitExceededException failure, got $other")
            end match
        }

        "schema decode rejects collections beyond maxCollectionSize without JSON bridging" in {
            val yaml =
                """- 1
                  |- 2
                  |""".stripMargin

            Yaml.decode[List[Int]](yaml, Yaml.DefaultMaxDepth, 1) match
                case Result.Failure(e: LimitExceededException) =>
                    assert(e.limit == "Collection size")
                case other => fail(s"Expected LimitExceededException failure, got $other")
            end match
        }

        "schema decode rejects mapping fields beyond maxCollectionSize without parsing later malformed content" in {
            val yaml =
                """name: Alice
                  |age: 30
                  |extra: 1
                  |later: [unterminated
                  |""".stripMargin

            Yaml.decode[MTPerson](yaml, Yaml.DefaultMaxDepth, 2) match
                case Result.Failure(e: LimitExceededException) =>
                    assert(e.limit == "Collection size")
                case other => fail(s"Expected LimitExceededException failure, got $other")
            end match
        }

        "schema decode accounts for expanded alias contents without JSON bridging" in {
            case class AliasLimit(items: List[Int], refs: List[List[Int]]) derives CanEqual

            val yaml =
                """items: &items
                  |  - 1
                  |  - 2
                  |refs:
                  |  - *items
                  |  - *items
                  |""".stripMargin

            Yaml.decode[AliasLimit](yaml, Yaml.DefaultMaxDepth, 3) match
                case Result.Failure(e: LimitExceededException) =>
                    assert(e.limit == "Collection size")
                case other => fail(s"Expected LimitExceededException failure, got $other")
            end match
        }

        "schema decode rejects oversized numeric scalars before numeric conversion" in {
            val yaml = "1e999999999\n"

            Yaml.decode[Double](yaml, Yaml.DefaultMaxDepth, 8) match
                case Result.Failure(e: LimitExceededException) =>
                    assert(e.limit == "Numeric scalar length")
                case other => fail(s"Expected LimitExceededException failure, got $other")
            end match
        }

        "schema decode applies collection limits before parsing later malformed content" in {
            val yaml =
                """- 1
                  |- 2
                  |- [unterminated
                  |""".stripMargin

            Yaml.decode[List[Int]](yaml, Yaml.DefaultMaxDepth, 1) match
                case Result.Failure(e: LimitExceededException) =>
                    assert(e.limit == "Collection size")
                case other => fail(s"Expected LimitExceededException failure, got $other")
            end match
        }

        "schema decode rejects compact BigDecimal exponent amplification" in {
            Yaml.decode[BigDecimal]("1e100000\n") match
                case Result.Failure(e: LimitExceededException) =>
                    assert(e.limit == "Numeric scalar exponent")
                case other => fail(s"Expected LimitExceededException failure, got $other")
            end match
        }
    }

    "parse" - {

        "builds a YAML node tree only when requested" in {
            val parsed = Yaml.parse("name: Alice\nage: 30\n").getOrThrow

            parsed match
                case Yaml.Node.Mapping(entries, _) =>
                    assert(entries.map(_._1.asInstanceOf[Yaml.Node.Scalar].value) == Chunk("name", "age"))
                    assert(entries.map(_._2.asInstanceOf[Yaml.Node.Scalar].value) == Chunk("Alice", "30"))
                case other => fail(s"Expected mapping, got $other")
            end match
        }

        "targets a document by zero-based index" in {
            val yaml =
                """---
                  |name: Alice
                  |age: 30
                  |---
                  |name: Bob
                  |age: 25
                  |""".stripMargin

            val parsed = Yaml.parse(yaml, Yaml.DocumentIndex(1)).getOrThrow

            parsed match
                case Yaml.Node.Mapping(entries, _) =>
                    val fields = entries.map {
                        case (Yaml.Node.Scalar(key, _), Yaml.Node.Scalar(value, _)) => key -> value
                        case other                                                  => fail(s"Expected scalar entry, got $other")
                    }.toMap
                    assert(fields("name") == "Bob")
                    assert(fields("age") == "25")
                case other => fail(s"Expected mapping, got $other")
            end match
        }
    }

    "visit" - {

        "streams block mapping events without requiring a YAML node tree" in {
            val visitor = new Yaml.Visitor[List[String], String, List[String]]:
                def streamStart(context: List[String], mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed("streamStart" :: context)

                def documentStart(context: List[String], mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed("documentStart" :: context)

                def mappingStart(context: List[String], meta: Yaml.Meta): Result[String, List[String]] =
                    Result.succeed(s"mappingStart:${meta.anchor.getOrElse("")}:${meta.tag.getOrElse("")}" :: context)

                def sequenceStart(context: List[String], meta: Yaml.Meta): Result[String, List[String]] =
                    Result.succeed(s"sequenceStart:${meta.anchor.getOrElse("")}:${meta.tag.getOrElse("")}" :: context)

                def scalar(context: List[String], value: String, meta: Yaml.ScalarMeta): Result[String, List[String]] =
                    Result.succeed(s"scalar:$value:${meta.style}" :: context)

                def alias(context: List[String], name: String, mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed(s"alias:$name" :: context)

                def nodeEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed("nodeEnd" :: context)

                def documentEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed("documentEnd" :: context)

                def streamEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed(("streamEnd" :: context).reverse)
            end visitor

            val visited = Yaml.visit("name: Alice\nage: 30\n", Nil)(visitor)

            assert(
                visited == Result.succeed(List(
                    "streamStart",
                    "documentStart",
                    "mappingStart::",
                    "scalar:name:Plain",
                    "scalar:Alice:Plain",
                    "scalar:age:Plain",
                    "scalar:30:Plain",
                    "nodeEnd",
                    "documentEnd",
                    "streamEnd"
                ))
            )
        }

        "exposes anchors and tags as visitor metadata" in {
            val visitor = new Yaml.Visitor[List[String], String, List[String]]:
                def streamStart(context: List[String], mark: Yaml.Mark): Result[String, List[String]]   = Result.succeed(context)
                def documentStart(context: List[String], mark: Yaml.Mark): Result[String, List[String]] = Result.succeed(context)

                def mappingStart(context: List[String], meta: Yaml.Meta): Result[String, List[String]] =
                    Result.succeed(s"map:${meta.anchor.getOrElse("")}:${meta.tag.getOrElse("")}" :: context)

                def sequenceStart(context: List[String], meta: Yaml.Meta): Result[String, List[String]] =
                    Result.succeed(s"seq:${meta.anchor.getOrElse("")}:${meta.tag.getOrElse("")}" :: context)

                def scalar(context: List[String], value: String, meta: Yaml.ScalarMeta): Result[String, List[String]] =
                    Result.succeed(s"scalar:$value:${meta.anchor.getOrElse("")}:${meta.tag.getOrElse("")}" :: context)

                def alias(context: List[String], name: String, mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed(s"alias:$name" :: context)

                def nodeEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]]     = Result.succeed(context)
                def documentEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]] = Result.succeed(context)
                def streamEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]]   = Result.succeed(context.reverse)
            end visitor

            val yaml =
                """value: !custom &id Alice
                  |again: *id
                  |""".stripMargin

            assert(
                Yaml.visit(yaml, Nil)(visitor) == Result.succeed(List(
                    "map::",
                    "scalar:value::",
                    "scalar:Alice:id:!custom",
                    "scalar:again::",
                    "alias:id"
                ))
            )
        }

        "targets a document by zero-based index" in {
            val visitor = new Yaml.Visitor[List[String], String, List[String]]:
                def streamStart(context: List[String], mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed(context)

                def documentStart(context: List[String], mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed(context)

                def mappingStart(context: List[String], meta: Yaml.Meta): Result[String, List[String]] =
                    Result.succeed(context)

                def sequenceStart(context: List[String], meta: Yaml.Meta): Result[String, List[String]] =
                    Result.succeed(context)

                def scalar(context: List[String], value: String, meta: Yaml.ScalarMeta): Result[String, List[String]] =
                    Result.succeed(value :: context)

                def alias(context: List[String], name: String, mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed(context)

                def nodeEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed(context)

                def documentEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed(context)

                def streamEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed(context.reverse)
            end visitor

            val yaml =
                """---
                  |name: Alice
                  |age: 30
                  |---
                  |name: Bob
                  |age: 25
                  |""".stripMargin

            assert(Yaml.visit(yaml, Yaml.DocumentIndex(1), Nil)(visitor) == Result.succeed(List("name", "Bob", "age", "25")))
        }
    }

    "real-world YAML" - {

        "decodes GitHub Actions workflow shapes" in {
            val yaml =
                """name: CI
                  |on:
                  |  push:
                  |    branches: [main, release]
                  |  pull_request:
                  |jobs:
                  |  build:
                  |    runs-on: ubuntu-latest
                  |    steps:
                  |      - name: Checkout
                  |        uses: actions/checkout@v4
                  |      - name: Test
                  |        run: |-
                  |          sbt 'kyo-schema/test'
                  |          echo done
                  |        with:
                  |          cache: sbt
                  |""".stripMargin

            val expected = YamlGithubWorkflow(
                "CI",
                YamlGithubOn(YamlGithubPush(List("main", "release")), None),
                Map(
                    "build" -> YamlGithubJob(
                        "ubuntu-latest",
                        List(
                            YamlGithubStep(Some("Checkout"), Some("actions/checkout@v4"), None, None),
                            YamlGithubStep(Some("Test"), None, Some("sbt 'kyo-schema/test'\necho done"), Some(Map("cache" -> "sbt")))
                        )
                    )
                )
            )

            assert(Yaml.decode[YamlGithubWorkflow](yaml) == Result.succeed(expected))
        }

        "decodes OpenAPI spec shapes" in {
            val yaml =
                """openapi: 3.1.0
                  |info:
                  |  title: Users API
                  |  version: 1.0.0
                  |  description: >-
                  |    Users API for
                  |    client apps.
                  |paths:
                  |  /users/{id}:
                  |    get:
                  |      summary: Get a user
                  |      operationId: getUser
                  |      parameters:
                  |        - name: id
                  |          in: path
                  |          required: true
                  |          schema: {type: string, format: uuid}
                  |      responses:
                  |        "200":
                  |          description: ok
                  |components:
                  |  schemas:
                  |    UserId: {type: string, format: uuid}
                  |""".stripMargin

            val expected = YamlOpenApiSpec(
                "3.1.0",
                YamlOpenApiInfo("Users API", "1.0.0", "Users API for client apps."),
                Map(
                    "/users/{id}" -> YamlOpenApiPathItem(
                        YamlOpenApiOperation(
                            "Get a user",
                            "getUser",
                            List(YamlOpenApiParameter("id", "path", true, YamlOpenApiSchema("string", Some("uuid")))),
                            Map("200" -> YamlOpenApiResponse("ok"))
                        )
                    )
                ),
                YamlOpenApiComponents(Map("UserId" -> YamlOpenApiSchema("string", Some("uuid"))))
            )

            assert(Yaml.decode[YamlOpenApiSpec](yaml) == Result.succeed(expected))
        }

        "decodes Docker Compose files" in {
            val yaml =
                """version: "3.9"
                  |x-logging: &default-logging
                  |  driver: json-file
                  |  options:
                  |    max-size: 10m
                  |    max-file: "3"
                  |services:
                  |  api:
                  |    image: ghcr.io/acme/api:1.2.3
                  |    ports:
                  |      - "8080:80"
                  |    environment:
                  |      APP_ENV: production
                  |      FEATURE_FLAG: "true"
                  |      REGION: NO
                  |    depends_on: [db, redis]
                  |    volumes:
                  |      - "app-data:/var/lib/app"
                  |      - "./config:/app/config:ro"
                  |    networks:
                  |      - backend
                  |    healthcheck:
                  |      test: ["CMD-SHELL", "curl -f http://localhost:8080/health || exit 1"]
                  |      interval: 30s
                  |      timeout: 10s
                  |      retries: 3
                  |    logging: *default-logging
                  |    restart: unless-stopped
                  |  db:
                  |    image: postgres:16
                  |    environment:
                  |      POSTGRES_DB: app
                  |      POSTGRES_PASSWORD: secret
                  |    volumes:
                  |      - "db-data:/var/lib/postgresql/data"
                  |    networks: [backend]
                  |volumes:
                  |  app-data:
                  |    driver: local
                  |  db-data:
                  |    driver: local
                  |networks:
                  |  backend:
                  |    driver: bridge
                  |""".stripMargin

            val logging = YamlComposeLogging("json-file", Map("max-size" -> "10m", "max-file" -> "3"))
            val expected = YamlDockerCompose(
                "3.9",
                logging,
                Map(
                    "api" -> YamlComposeService(
                        "ghcr.io/acme/api:1.2.3",
                        Some(List("8080:80")),
                        Some(Map("APP_ENV" -> "production", "FEATURE_FLAG" -> "true", "REGION" -> "NO")),
                        Some(List("db", "redis")),
                        Some(List("app-data:/var/lib/app", "./config:/app/config:ro")),
                        Some(List("backend")),
                        Some(YamlComposeHealthcheck(
                            List("CMD-SHELL", "curl -f http://localhost:8080/health || exit 1"),
                            "30s",
                            "10s",
                            3
                        )),
                        Some(logging),
                        Some("unless-stopped")
                    ),
                    "db" -> YamlComposeService(
                        "postgres:16",
                        None,
                        Some(Map("POSTGRES_DB" -> "app", "POSTGRES_PASSWORD" -> "secret")),
                        None,
                        Some(List("db-data:/var/lib/postgresql/data")),
                        Some(List("backend")),
                        None,
                        None,
                        None
                    )
                ),
                Map("app-data" -> YamlComposeVolume(Some("local")), "db-data" -> YamlComposeVolume(Some("local"))),
                Map("backend"  -> YamlComposeNetwork(Some("bridge")))
            )

            assert(Yaml.decode[YamlDockerCompose](yaml) == Result.succeed(expected))
        }
    }

    "YAML 1.2 scenarios" - {

        "uses Core schema scalar resolution and avoids the Norway problem" in {
            val yaml =
                """norway: NO
                  |on: on
                  |off: off
                  |yes: yes
                  |no: no
                  |trueValue: true
                  |falseValue: FALSE
                  |nullValue: ~
                  |""".stripMargin

            assert(Yaml.decode[YamlCoreScalars](yaml) == Result.succeed(
                YamlCoreScalars("NO", "on", "off", "yes", "no", true, false, None)
            ))
        }

        "decodes dash-only sequence entries with nested mappings" in {
            val yaml =
                """-
                  |  name: Mark McGwire
                  |  hr: 65
                  |  avg: 0.278
                  |-
                  |  name: Sammy Sosa
                  |  hr: 63
                  |  avg: 0.288
                  |""".stripMargin

            assert(Yaml.decode[List[YamlPlayer]](yaml) == Result.succeed(
                List(YamlPlayer("Mark McGwire", 65, 0.278), YamlPlayer("Sammy Sosa", 63, 0.288))
            ))
        }

        "decodes indentless block sequences as mapping values" in {
            val yaml =
                """american:
                  |- Boston Red Sox
                  |- Detroit Tigers
                  |national:
                  |- New York Mets
                  |- Chicago Cubs
                  |""".stripMargin

            assert(Yaml.decode[YamlLeagues](yaml) == Result.succeed(
                YamlLeagues(List("Boston Red Sox", "Detroit Tigers"), List("New York Mets", "Chicago Cubs"))
            ))
        }

        "decodes double quoted escape sequences" in {
            val yaml =
                """unicode: "Sosa did fine.\u263A"
                  |control: "\b1998\t1999\t2000\n"
                  |hex: "\x0d\x0a is \r\n"
                  |""".stripMargin

            assert(Yaml.decode[YamlEscapes](yaml) == Result.succeed(
                YamlEscapes("Sosa did fine.\u263A", "\b1998\t1999\t2000\n", "\r\n is \r\n")
            ))
        }

        "decodes quoted block mapping keys containing colons" in {
            val yaml =
                """"http://example.com/users": users
                  |'urn:service:orders': orders
                  |plain: value
                  |""".stripMargin

            assert(Yaml.decode[Map[String, String]](yaml) == Result.succeed(Map(
                "http://example.com/users" -> "users",
                "urn:service:orders"       -> "orders",
                "plain"                    -> "value"
            )))
        }

        "decodes multiline flow collections and scalars" in {
            val person =
                """{
                  |  name: Alice,
                  |  age: 30
                  |}
                  |""".stripMargin
            val numbers =
                """[
                  |  1,
                  |  2,
                  |  3
                  |]
                  |""".stripMargin
            val text =
                """{
                  |  doubleQuoted: "double
                  |    quoted",
                  |  plain: plain
                  |    scalar
                  |}
                  |""".stripMargin

            assert(Yaml.decode[MTPerson](person) == Result.succeed(MTPerson("Alice", 30)))
            assert(Yaml.decode[List[Int]](numbers) == Result.succeed(List(1, 2, 3)))
            assert(Yaml.decode[YamlFlowText](text) == Result.succeed(YamlFlowText("double quoted", "plain scalar")))
        }

        "keeps parser and schema decode aligned for tricky flow collection scanning" in {
            val yaml =
                """{
                  |  items: [
                  |    { name: "Alice # admin", url: https://example.com/a:b, labels: [one, "two, too"] }, # trailing comment
                  |    { name: Bob, url: 'urn:svc:orders', labels: [three] }
                  |  ]
                  |}
                  |""".stripMargin
            val expected = YamlFlowScanDoc(
                List(
                    YamlFlowScanItem("Alice # admin", "https://example.com/a:b", List("one", "two, too")),
                    YamlFlowScanItem("Bob", "urn:svc:orders", List("three"))
                )
            )

            assert(Yaml.decode[YamlFlowScanDoc](yaml) == Result.succeed(expected))
            Yaml.parse(yaml).getOrThrow match
                case Yaml.Node.Mapping(entries, _) =>
                    assert(entries.size == 1)
                    entries(0) match
                        case (Yaml.Node.Scalar("items", _), Yaml.Node.Sequence(items, _)) =>
                            assert(items.size == 2)
                        case other => fail(s"Expected items sequence, got $other")
                    end match
                case other => fail(s"Expected flow mapping root, got $other")
            end match
        }

        "parses flow sequence entries that are single pair mappings" in {
            val parsed = Yaml.parse("[single: pair]").getOrThrow

            parsed match
                case Yaml.Node.Sequence(elements, _) =>
                    assert(elements.size == 1)
                    elements(0) match
                        case Yaml.Node.Mapping(entries, _) =>
                            assert(entries.map {
                                case (Yaml.Node.Scalar(key, _), Yaml.Node.Scalar(value, _)) => key -> value
                                case other => fail(s"Expected scalar mapping entry, got $other")
                            } == Chunk("single" -> "pair"))
                        case other => fail(s"Expected mapping element, got $other")
                    end match
                case other => fail(s"Expected sequence, got $other")
            end match
        }

        "treats adjacent colons and URLs as plain keys in flow mappings" in {
            val parsed = Yaml.parse("{a:1, https://example.com, b: 2}").getOrThrow

            parsed match
                case Yaml.Node.Mapping(entries, _) =>
                    assert(entries.map {
                        case (Yaml.Node.Scalar(key, _), Yaml.Node.Scalar(value, _)) => key -> value
                        case other                                                  => fail(s"Expected scalar mapping entry, got $other")
                    } == Chunk(
                        "a:1"                 -> "",
                        "https://example.com" -> "",
                        "b"                   -> "2"
                    ))
                case other => fail(s"Expected mapping, got $other")
            end match
        }

        "decodes JSON-style adjacent flow mapping values" in {
            assert(Yaml.decode[Map[String, Int]]("""{"a":1, b: 2}""") == Result.succeed(Map("a" -> 1, "b" -> 2)))
        }

        "reports invalid double quoted escapes as parse failures" in {
            val result = Yaml.decode[Map[String, String]]("""bad: "\xZZ"""")

            result match
                case Result.Failure(e: ParseException) =>
                    assert(e.getMessage.contains("Invalid escape sequence"))
                    assert(e.getMessage.contains("line 1"))
                    assert(e.getMessage.contains("column"))
                case other => fail(s"Expected ParseException failure, got $other")
            end match
        }

        "folds block scalars by paragraph while preserving more-indented lines" in {
            val yaml =
                """text: >-
                  |  folded
                  |  line
                  |
                  |  next
                  |  line
                  |    code
                  |    block
                  |
                  |  last
                  |  line
                  |""".stripMargin

            assert(Yaml.decode[Map[String, String]](yaml) == Result.succeed(Map(
                "text" -> "folded line\nnext line\n  code\n  block\n\nlast line"
            )))
        }

        "decodes multiline scalar styles and block scalar indicators" in {
            val yaml =
                """literalStrip: |-
                  |  line one
                  |  line two
                  |
                  |literalClip: |
                  |  line one
                  |  line two
                  |
                  |literalKeep: |+
                  |  line one
                  |  line two
                  |
                  |
                  |literalIndent: |4
                  |    indented
                  |      deeper
                  |
                  |foldedStrip: >2-
                  |  line one
                  |  line two
                  |
                  |  line three
                  |foldedKeep: >+2
                  |  line one
                  |  line two
                  |
                  |
                  |singleQuoted: 'line one
                  |  line two
                  |
                  |  line three'
                  |doubleQuoted: "line one
                  |  line two\nline three"
                  |plain: line one
                  |  line two
                  |
                  |  line three
                  |""".stripMargin

            assert(Yaml.decode[YamlMultilineScalars](yaml) == Result.succeed(YamlMultilineScalars(
                literalStrip = "line one\nline two",
                literalClip = "line one\nline two\n",
                literalKeep = "line one\nline two\n\n\n",
                literalIndent = "indented\n  deeper\n",
                foldedStrip = "line one line two\nline three",
                foldedKeep = "line one line two\n\n\n",
                singleQuoted = "line one line two\nline three",
                doubleQuoted = "line one line two\nline three",
                plain = "line one line two\nline three"
            )))
        }

        "infers block scalar indentation from the first non-empty content line" in {
            val yaml =
                """wide: |
                  |    text
                  |      deeper
                  |leadingBlank: |-
                  |
                  |    text
                  |explicit: |2
                  |  text
                  |""".stripMargin

            assert(Yaml.decode[Map[String, String]](yaml) == Result.succeed(Map(
                "wide"         -> "text\n  deeper\n",
                "leadingBlank" -> "\ntext",
                "explicit"     -> "text\n"
            )))
        }

        "reports less-indented non-empty block scalar content" in {
            val yaml =
                """text: |
                  |    first
                  |  second
                  |""".stripMargin

            Yaml.decode[Map[String, String]](yaml) match
                case Result.Failure(e: ParseException) =>
                    assert(e.getMessage.contains("Expected block scalar indentation"))
                    assert(e.getMessage.contains("line 3"))
                case other => fail(s"Expected ParseException failure, got $other")
            end match
        }

        "keeps the yaml document from hell gotchas predictable" in {
            val yaml =
                """server_config:
                  |  port_mapping:
                  |    - 22:22
                  |    - 80:80
                  |    - 443:443
                  |  serve:
                  |    - /robots.txt
                  |    - /favicon.ico
                  |    - "*.html"
                  |    - "*.png"
                  |    - "!.git"
                  |  geoblock_regions:
                  |    - dk
                  |    - fi
                  |    - is
                  |    - no
                  |    - se
                  |  flush_cache:
                  |    on: [push, memory_pressure]
                  |    priority: background
                  |  allow_postgres_versions:
                  |    - 9.5.25
                  |    - 9.6.24
                  |    - 10.23
                  |    - 12.13
                  |""".stripMargin

            val parsed = Yaml.parse(yaml).getOrThrow
            val server = field(parsed, "server_config")
            val versions = field(server, "allow_postgres_versions").asInstanceOf[Yaml.Node.Sequence].elements.map {
                case Yaml.Node.Scalar(value, _) => value
                case other                      => fail(s"Expected scalar version, got $other")
            }

            assert(versions == Chunk("9.5.25", "9.6.24", "10.23", "12.13"))
            assert(Yaml.decode[YamlHellConfig](yaml) == Result.succeed(YamlHellConfig(
                YamlHellServerConfig(
                    List("22:22", "80:80", "443:443"),
                    List("/robots.txt", "/favicon.ico", "*.html", "*.png", "!.git"),
                    List("dk", "fi", "is", "no", "se"),
                    YamlHellFlushCache(List("push", "memory_pressure"), "background")
                )
            )))
        }

        "reports unquoted aliases from the yaml document from hell as unknown aliases" in {
            val result = Yaml.decode[Map[String, List[String]]](
                """serve:
                  |  - /robots.txt
                  |  - *.html
                  |""".stripMargin
            )

            result match
                case Result.Failure(e: ParseException) =>
                    assert(e.getMessage.contains("Unknown alias '.html'"))
                    assert(e.getMessage.contains("line 3"))
                case other => fail(s"Expected ParseException failure, got $other")
            end match
        }

        "treats local tags from the yaml document from hell as metadata only" in {
            val visitor = new Yaml.Visitor[List[String], String, List[String]]:
                def streamStart(context: List[String], mark: Yaml.Mark): Result[String, List[String]]         = Result.succeed(context)
                def documentStart(context: List[String], mark: Yaml.Mark): Result[String, List[String]]       = Result.succeed(context)
                def mappingStart(context: List[String], meta: Yaml.Meta): Result[String, List[String]]        = Result.succeed(context)
                def sequenceStart(context: List[String], meta: Yaml.Meta): Result[String, List[String]]       = Result.succeed(context)
                def alias(context: List[String], name: String, mark: Yaml.Mark): Result[String, List[String]] = Result.succeed(context)
                def nodeEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]]             = Result.succeed(context)
                def documentEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]]         = Result.succeed(context)
                def streamEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]] = Result.succeed(context.reverse)

                def scalar(context: List[String], value: String, meta: Yaml.ScalarMeta): Result[String, List[String]] =
                    Result.succeed(s"$value:${meta.tag.getOrElse("")}" :: context)
            end visitor

            assert(Yaml.visit("serve:\n  - !.git\n", Nil)(visitor) == Result.succeed(List("serve:", ":!.git")))
        }

        "parses YAML 1.2 decimal integers with leading zeroes as decimal" in {
            assert(Yaml.decode[Map[String, Int]]("value: 010\n") == Result.succeed(Map("value" -> 10)))
        }

        "resolves YAML 1.2 Core schema numeric scalars" in {
            val yaml =
                """octal: 0o7
                  |hex: 0x3A
                  |leadingDot: .5
                  |positiveExponent: +12e03
                  |positiveInfinity: .inf
                  |negativeInfinity: -.Inf
                  |nan: .NAN
                  |""".stripMargin

            val decoded = Yaml.decode[YamlCoreNumbers](yaml).getOrThrow

            assert(decoded.octal == 7)
            assert(decoded.hex == 58)
            assert(decoded.leadingDot == 0.5)
            assert(decoded.positiveExponent == 12000.0)
            assert(decoded.positiveInfinity.isPosInfinity)
            assert(decoded.negativeInfinity.isNegInfinity)
            assert(decoded.nan.isNaN)
        }

        "honors standard explicit scalar tags during schema decode" in {
            val yaml =
                """stringValue: !!str true
                  |intValue: !!int "0x3A"
                  |boolValue: !!bool "false"
                  |floatValue: !!float ".inf"
                  |nullValue: !!null ignored
                  |disabled: ! 12
                  |""".stripMargin

            val decoded = Yaml.decode[YamlTaggedScalars](yaml).getOrThrow

            assert(decoded.stringValue == "true")
            assert(decoded.intValue == 58)
            assert(!decoded.boolValue)
            assert(decoded.floatValue.isPosInfinity)
            assert(decoded.nullValue == None)
            assert(decoded.disabled == "12")
        }

        "uses YAML 1.1 scalar resolution when configured" in {
            val yaml =
                """norway: NO
                  |on: on
                  |off: off
                  |yes: yes
                  |no: no
                  |y: Y
                  |n: n
                  |""".stripMargin
            val config = Yaml.ReaderConfig(yamlVersion = Yaml.SpecVersion.Yaml11)

            assert(Yaml.decode[YamlLegacyBooleans](yaml, config) == Result.succeed(
                YamlLegacyBooleans(false, true, false, true, false, true, false)
            ))
        }

        "decodes YAML 1.1 numeric scalar forms when configured" in {
            val yaml =
                """octal: 010
                  |binary: 0b1010
                  |sexagesimal: 1:20:30
                  |underscored: +685_230
                  |fixed: 685_230.15
                  |sexagesimalFloat: 190:20:30.15
                  |""".stripMargin
            val config  = Yaml.ReaderConfig(yamlVersion = Yaml.SpecVersion.Yaml11)
            val decoded = Yaml.decode[YamlLegacyNumbers](yaml, config).getOrThrow

            assert(decoded.octal == 8)
            assert(decoded.binary == 10)
            assert(decoded.sexagesimal == 4830)
            assert(decoded.underscored == 685230)
            assert(decoded.fixed == 685230.15)
            assert(decoded.sexagesimalFloat == 685230.15)
        }

        "honors YAML 1.1 explicit scalar tags when configured" in {
            val yaml =
                """intValue: !!int "010"
                  |boolValue: !!bool "NO"
                  |floatValue: !!float "190:20:30.15"
                  |""".stripMargin
            val config = Yaml.ReaderConfig(yamlVersion = Yaml.SpecVersion.Yaml11)

            val decoded = Yaml.decode[Map[String, String]]("value: NO\n").getOrThrow

            assert(decoded == Map("value" -> "NO"))
            assert(Yaml.decode[YamlLegacyTaggedScalars](yaml, config) == Result.succeed(
                YamlLegacyTaggedScalars(8, false, 685230.15)
            ))
        }
    }

    private def field(node: Yaml.Node, name: String): Yaml.Node =
        node match
            case Yaml.Node.Mapping(entries, _) =>
                entries.collectFirst {
                    case (Yaml.Node.Scalar(`name`, _), value) => value
                }.getOrElse(fail(s"Missing field $name"))
            case other => fail(s"Expected mapping for field $name, got $other")
        end match
    end field

end YamlParserTest
