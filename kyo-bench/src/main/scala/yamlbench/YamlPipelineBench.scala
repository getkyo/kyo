package yamlbench

import kyo.*
import kyo.bench.BaseBench
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized

class YamlPipelineBench extends BaseBench:

    @Param(Array("small", "nested", "wide", "collection", "workflow", "openapi", "compose"))
    var payload: String = uninitialized

    private val identityPipeline =
        Yaml.pipeline.through(YamlPipelineBench.IdentityScalarProcessor)

    private var baselineDecode: () => Any           = uninitialized
    private var noProcessorDecode: () => Any        = uninitialized
    private var identityProcessorDecode: () => Any  = uninitialized
    private var renderThenDecodePipeline: () => Any = uninitialized

    @Setup(Level.Trial)
    def setup(): Unit =
        payload match
            case "small" =>
                configure[YamlSmall](YamlBenchData.smallYaml)(YamlBenchData.decodeSmall)
            case "nested" =>
                configure[YamlNested](YamlBenchData.nestedYaml)(YamlBenchData.decodeNested)
            case "wide" =>
                configure[YamlWide](YamlBenchData.wideYaml)(YamlBenchData.decodeWide)
            case "collection" =>
                configure[YamlCollection](YamlBenchData.collectionYaml)(YamlBenchData.decodeCollection)
            case "workflow" =>
                configure[YamlWorkflow](YamlBenchData.workflowYaml)(YamlBenchData.decodeWorkflow)
            case "openapi" =>
                configure[YamlOpenApi](YamlBenchData.openApiYaml)(YamlBenchData.decodeOpenApi)
            case "compose" =>
                configure[YamlCompose](YamlBenchData.composeYaml)(YamlBenchData.decodeCompose)
        end match
    end setup

    private def configure[A](input: String)(decode: String => A)(using Schema[A]): Unit =
        baselineDecode = () => decode(input)
        noProcessorDecode = () => Yaml.pipeline.decode[A](input).getOrThrow
        identityProcessorDecode = () => identityPipeline.decode[A](input).getOrThrow
        renderThenDecodePipeline = () => decode(identityPipeline.render(input).getOrThrow)
    end configure

    @Benchmark def baseline(): Any =
        baselineDecode()

    @Benchmark def noProcessorPipeline(): Any =
        noProcessorDecode()

    @Benchmark def identityProcessorPipeline(): Any =
        identityProcessorDecode()

    @Benchmark def renderThenDecode(): Any =
        renderThenDecodePipeline()
end YamlPipelineBench

private object YamlPipelineBench:

    val IdentityScalarProcessor: Yaml.Events.Processor[DecodeException] =
        Yaml.Events.Processor.mapScalars[DecodeException]((value, meta) => Result.succeed((value, meta)))
end YamlPipelineBench
