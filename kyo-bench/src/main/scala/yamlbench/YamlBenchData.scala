package yamlbench

import kyo.*

case class YamlSmall(name: String, age: Int, active: Boolean) derives CanEqual, Schema

case class YamlNested(user: YamlSmall, score: Double, tags: List[String]) derives CanEqual, Schema

case class YamlWide(
    f1: String,
    f2: Int,
    f3: Boolean,
    f4: Double,
    f5: String,
    f6: Int,
    f7: Boolean,
    f8: Double,
    f9: String,
    f10: Int
) derives CanEqual, Schema

case class YamlCollection(items: List[YamlSmall]) derives CanEqual, Schema

case class YamlWorkflow(on: List[String], jobs: Map[String, YamlWorkflowJob]) derives CanEqual, Schema
case class YamlWorkflowJob(runsOn: String, steps: List[YamlWorkflowStep]) derives CanEqual, Schema
case class YamlWorkflowStep(name: String, uses: Maybe[String], run: Maybe[String]) derives CanEqual, Schema

case class YamlOpenApi(openapi: String, info: YamlOpenApiInfo, paths: Map[String, YamlOpenApiPath]) derives CanEqual, Schema
case class YamlOpenApiInfo(title: String, version: String) derives CanEqual, Schema
case class YamlOpenApiPath(get: YamlOpenApiOperation) derives CanEqual, Schema
case class YamlOpenApiOperation(summary: String, responses: Map[String, YamlOpenApiResponse]) derives CanEqual, Schema
case class YamlOpenApiResponse(description: String) derives CanEqual, Schema

case class YamlCompose(services: Map[String, YamlComposeService], volumes: Map[String, Map[String, String]]) derives CanEqual, Schema
case class YamlComposeService(image: String, ports: List[String], environment: Map[String, String]) derives CanEqual, Schema

object YamlBenchData:

    val small: YamlSmall =
        YamlSmall("Alice", 30, active = true)

    val nested: YamlNested =
        YamlNested(YamlSmall("Bob", 25, active = false), 99.5, List("scala", "kyo", "yaml"))

    val wide: YamlWide =
        YamlWide("a", 1, true, 1.1, "b", 2, false, 2.2, "c", 3)

    val collection: YamlCollection =
        YamlCollection(List.fill(100)(YamlSmall("Item", 42, active = true)))

    val workflow: YamlWorkflow =
        YamlWorkflow(
            on = List("push", "pull_request"),
            jobs = Map(
                "build" -> YamlWorkflowJob(
                    runsOn = "ubuntu-latest",
                    steps = List(
                        YamlWorkflowStep("Checkout", Maybe("actions/checkout@v4"), Absent),
                        YamlWorkflowStep("Setup Java", Maybe("actions/setup-java@v4"), Absent),
                        YamlWorkflowStep("Test", Absent, Maybe("sbt kyo-schema/test"))
                    )
                ),
                "lint" -> YamlWorkflowJob(
                    runsOn = "ubuntu-latest",
                    steps = List(
                        YamlWorkflowStep("Checkout", Maybe("actions/checkout@v4"), Absent),
                        YamlWorkflowStep("Format", Absent, Maybe("sbt scalafmtCheckAll"))
                    )
                )
            )
        )

    val openApi: YamlOpenApi =
        YamlOpenApi(
            openapi = "3.1.0",
            info = YamlOpenApiInfo("Kyo API", "1.0.0"),
            paths = Map(
                "/users" -> YamlOpenApiPath(
                    YamlOpenApiOperation(
                        summary = "List users",
                        responses = Map(
                            "200" -> YamlOpenApiResponse("Users returned"),
                            "500" -> YamlOpenApiResponse("Server error")
                        )
                    )
                ),
                "/users/{id}" -> YamlOpenApiPath(
                    YamlOpenApiOperation(
                        summary = "Get user",
                        responses = Map(
                            "200" -> YamlOpenApiResponse("User returned"),
                            "404" -> YamlOpenApiResponse("User missing")
                        )
                    )
                )
            )
        )

    val compose: YamlCompose =
        YamlCompose(
            services = Map(
                "web" -> YamlComposeService(
                    image = "example/web:latest",
                    ports = List("8080:8080", "8443:8443"),
                    environment = Map("DATABASE_URL" -> "postgres://db:5432/app", "CACHE_URL" -> "redis://cache:6379")
                ),
                "db" -> YamlComposeService(
                    image = "postgres:16",
                    ports = List("5432:5432"),
                    environment = Map("POSTGRES_DB" -> "app", "POSTGRES_USER" -> "kyo")
                )
            ),
            volumes = Map("db-data" -> Map.empty)
        )

    val smallYaml: String      = Yaml.encode(small)
    val nestedYaml: String     = Yaml.encode(nested)
    val wideYaml: String       = Yaml.encode(wide)
    val collectionYaml: String = Yaml.encode(collection)
    val workflowYaml: String   = Yaml.encode(workflow)
    val openApiYaml: String    = Yaml.encode(openApi)
    val composeYaml: String    = Yaml.encode(compose)

    def encodeSmall(config: Yaml.WriterConfig): String =
        Yaml.encode(small, config)

    def encodeNested(config: Yaml.WriterConfig): String =
        Yaml.encode(nested, config)

    def encodeWide(config: Yaml.WriterConfig): String =
        Yaml.encode(wide, config)

    def encodeCollection(config: Yaml.WriterConfig): String =
        Yaml.encode(collection, config)

    def encodeWorkflow(config: Yaml.WriterConfig): String =
        Yaml.encode(workflow, config)

    def encodeOpenApi(config: Yaml.WriterConfig): String =
        Yaml.encode(openApi, config)

    def encodeCompose(config: Yaml.WriterConfig): String =
        Yaml.encode(compose, config)

    def decodeSmall(input: String): YamlSmall =
        Yaml.decode[YamlSmall](input).getOrThrow

    def decodeNested(input: String): YamlNested =
        Yaml.decode[YamlNested](input).getOrThrow

    def decodeWide(input: String): YamlWide =
        Yaml.decode[YamlWide](input).getOrThrow

    def decodeCollection(input: String): YamlCollection =
        Yaml.decode[YamlCollection](input).getOrThrow

    def decodeWorkflow(input: String): YamlWorkflow =
        Yaml.decode[YamlWorkflow](input).getOrThrow

    def decodeOpenApi(input: String): YamlOpenApi =
        Yaml.decode[YamlOpenApi](input).getOrThrow

    def decodeCompose(input: String): YamlCompose =
        Yaml.decode[YamlCompose](input).getOrThrow

    def parse(input: String): Yaml.Node =
        Yaml.parse(input).getOrThrow

    def writerConfig(name: String): Yaml.WriterConfig =
        name match
            case "default" => Yaml.WriterConfig.Default
            case "small"   => Yaml.WriterConfig.Small
            case "fast"    => Yaml.WriterConfig.Fast
end YamlBenchData
