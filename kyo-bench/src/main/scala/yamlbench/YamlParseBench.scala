package yamlbench

import kyo.bench.BaseBench
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized

class YamlParseBench extends BaseBench:

    @Param(Array("small", "nested", "wide", "collection", "workflow", "openapi", "compose"))
    var payload: String = uninitialized

    private var parseKyo: () => Any = uninitialized

    @Setup(Level.Trial)
    def setup(): Unit =
        payload match
            case "small" =>
                val yaml = YamlBenchData.smallYaml
                parseKyo = () => YamlBenchData.parse(yaml)
            case "nested" =>
                val yaml = YamlBenchData.nestedYaml
                parseKyo = () => YamlBenchData.parse(yaml)
            case "wide" =>
                val yaml = YamlBenchData.wideYaml
                parseKyo = () => YamlBenchData.parse(yaml)
            case "collection" =>
                val yaml = YamlBenchData.collectionYaml
                parseKyo = () => YamlBenchData.parse(yaml)
            case "workflow" =>
                val yaml = YamlBenchData.workflowYaml
                parseKyo = () => YamlBenchData.parse(yaml)
            case "openapi" =>
                val yaml = YamlBenchData.openApiYaml
                parseKyo = () => YamlBenchData.parse(yaml)
            case "compose" =>
                val yaml = YamlBenchData.composeYaml
                parseKyo = () => YamlBenchData.parse(yaml)
        end match
    end setup

    @Benchmark def kyo(): Any =
        parseKyo()
end YamlParseBench
