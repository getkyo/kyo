package yamlbench

import kyo.bench.BaseBench
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized

class YamlDecodeBench extends BaseBench:

    @Param(Array("small", "nested", "wide", "collection", "workflow", "openapi", "compose"))
    var payload: String = uninitialized

    private var decodeKyo: () => Any = uninitialized

    @Setup(Level.Trial)
    def setup(): Unit =
        payload match
            case "small" =>
                val yaml = YamlBenchData.smallYaml
                decodeKyo = () => YamlBenchData.decodeSmall(yaml)
            case "nested" =>
                val yaml = YamlBenchData.nestedYaml
                decodeKyo = () => YamlBenchData.decodeNested(yaml)
            case "wide" =>
                val yaml = YamlBenchData.wideYaml
                decodeKyo = () => YamlBenchData.decodeWide(yaml)
            case "collection" =>
                val yaml = YamlBenchData.collectionYaml
                decodeKyo = () => YamlBenchData.decodeCollection(yaml)
            case "workflow" =>
                val yaml = YamlBenchData.workflowYaml
                decodeKyo = () => YamlBenchData.decodeWorkflow(yaml)
            case "openapi" =>
                val yaml = YamlBenchData.openApiYaml
                decodeKyo = () => YamlBenchData.decodeOpenApi(yaml)
            case "compose" =>
                val yaml = YamlBenchData.composeYaml
                decodeKyo = () => YamlBenchData.decodeCompose(yaml)
        end match
    end setup

    @Benchmark def kyo(): Any =
        decodeKyo()
end YamlDecodeBench
