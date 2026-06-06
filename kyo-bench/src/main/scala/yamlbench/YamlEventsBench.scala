package yamlbench

import kyo.*
import kyo.bench.BaseBench
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized

class YamlEventsBench extends BaseBench:

    @Param(Array("small", "nested", "wide", "collection", "workflow", "openapi", "compose"))
    var payload: String = uninitialized

    private var yaml: String = uninitialized

    @Setup(Level.Trial)
    def setup(): Unit =
        yaml = payload match
            case "small"      => YamlBenchData.smallYaml
            case "nested"     => YamlBenchData.nestedYaml
            case "wide"       => YamlBenchData.wideYaml
            case "collection" => YamlBenchData.collectionYaml
            case "workflow"   => YamlBenchData.workflowYaml
            case "openapi"    => YamlBenchData.openApiYaml
            case "compose"    => YamlBenchData.composeYaml
    end setup

    @Benchmark def visit(): Int =
        Yaml.Events.visit(yaml, 0)(CountingHandler).getOrThrow
    end visit

    @Benchmark def renderVisited(): String =
        val renderer = Yaml.Events.Renderer(Yaml.WriterConfig.Default)
        Yaml.Events.visit(yaml, ())(renderer).getOrThrow
        renderer.resultString
    end renderVisited

    @Benchmark def transformAndRenderVisited(): String =
        val renderer = Yaml.Events.Renderer(Yaml.WriterConfig.Default)
        Yaml.Events.visit(yaml, ())(IdentityScalarProcessor.andThen(renderer)).getOrThrow
        renderer.resultString
    end transformAndRenderVisited
end YamlEventsBench

private object CountingHandler extends Yaml.Events.Handler[Int, Nothing]:

    override def streamStart(context: Int, mark: Yaml.Mark): Result[Nothing, Int] =
        Result.succeed(context + 1)

    override def documentStart(context: Int, mark: Yaml.Mark): Result[Nothing, Int] =
        Result.succeed(context + 1)

    override def mappingStart(context: Int, meta: Yaml.Meta, size: Maybe[Int]): Result[Nothing, Int] =
        Result.succeed(context + 1)

    override def sequenceStart(context: Int, meta: Yaml.Meta, size: Maybe[Int]): Result[Nothing, Int] =
        Result.succeed(context + 1)

    override def scalar(context: Int, value: String, meta: Yaml.ScalarMeta): Result[Nothing, Int] =
        Result.succeed(context + 1)

    override def alias(context: Int, name: Yaml.Anchor, mark: Yaml.Mark): Result[Nothing, Int] =
        Result.succeed(context + 1)

    override def collectionEnd(context: Int, kind: Yaml.Events.CollectionKind, mark: Yaml.Mark): Result[Nothing, Int] =
        Result.succeed(context + 1)

    override def documentEnd(context: Int, mark: Yaml.Mark): Result[Nothing, Int] =
        Result.succeed(context + 1)

    override def streamEnd(context: Int, mark: Yaml.Mark): Result[Nothing, Int] =
        Result.succeed(context + 1)
end CountingHandler

private val IdentityScalarProcessor =
    Yaml.Events.Processor.mapScalars[DecodeException]((value, meta) => Result.succeed((value, meta)))
