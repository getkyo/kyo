package yamlbench

import kyo.*
import kyo.bench.BaseBench
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized

class YamlCstBench extends BaseBench:

    @Param(Array("small", "nested", "wide", "collection", "workflow", "openapi", "compose"))
    var payload: String = uninitialized

    private var parseSource: () => Any                 = uninitialized
    private var parseAndRenderSource: () => String     = uninitialized
    private var materializeEvents: () => Any           = uninitialized
    private var materializePipeline: () => Any         = uninitialized
    private var parseEditAndRenderSource: () => String = uninitialized

    @Setup(Level.Trial)
    def setup(): Unit =
        val yaml =
            payload match
                case "small"      => YamlBenchData.smallYaml
                case "nested"     => YamlBenchData.nestedYaml
                case "wide"       => YamlBenchData.wideYaml
                case "collection" => YamlBenchData.collectionYaml
                case "workflow"   => YamlBenchData.workflowYaml
                case "openapi"    => YamlBenchData.openApiYaml
                case "compose"    => YamlBenchData.composeYaml
        val sourceDoc =
            Yaml.cst(yaml).getOrThrow
        val events =
            sourceDoc.events
        val editPath =
            YamlCstBench.editPath(payload)
        val replacement =
            YamlCstBench.scalar("edited")

        parseSource = () => Yaml.cst(yaml).getOrThrow
        parseAndRenderSource = () => Yaml.cst(yaml).getOrThrow.render(using Yaml.WriterConfig.Default)
        materializeEvents = () => Yaml.Cst.fromEvents(events).getOrThrow
        materializePipeline = () => YamlCstBench.identityPipeline.cst(yaml).getOrThrow
        parseEditAndRenderSource = () =>
            Yaml.cst(yaml).getOrThrow
                .replace(editPath, replacement)
                .getOrThrow
                .render(using Yaml.WriterConfig.Default)
    end setup

    @Benchmark def sourceBackedParse(): Any =
        parseSource()

    @Benchmark def sourceBackedParseAndRenderDefault(): String =
        parseAndRenderSource()

    @Benchmark def canonicalFromPrecomputedEvents(): Any =
        materializeEvents()

    @Benchmark def identityPipelineCst(): Any =
        materializePipeline()

    @Benchmark def parseEditAndRender(): String =
        parseEditAndRenderSource()
end YamlCstBench

private object YamlCstBench:

    private val identityPipeline =
        Yaml.pipeline.through(IdentityScalarProcessor)

    private val IdentityScalarProcessor: Yaml.Events.Processor[DecodeException] =
        Yaml.Events.Processor.mapScalars[DecodeException]((value, meta) => Result.succeed((value, meta)))

    private def editPath(payload: String): Yaml.Cst.Path =
        payload match
            case "small"      => Yaml.Cst.Path.root / "name"
            case "nested"     => Yaml.Cst.Path.root / "user" / "name"
            case "wide"       => Yaml.Cst.Path.root / "f1"
            case "collection" => Yaml.Cst.Path.root / "items" / 0 / "name"
            case "workflow"   => Yaml.Cst.Path.root / "jobs" / "build" / "steps" / 0 / "name"
            case "openapi"    => Yaml.Cst.Path.root / "info" / "title"
            case "compose"    => Yaml.Cst.Path.root / "services" / "web" / "image"
    end editPath

    private def scalar(value: String): Yaml.Cst.Node =
        val mark = Yaml.Mark(0, 1, 1)
        val span = Yaml.Cst.SourceSpan(mark, mark)
        Yaml.Cst.Node.Scalar(
            value,
            Yaml.Cst.ScalarSyntax.Canonical,
            Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark),
            span,
            Absent
        )
    end scalar
end YamlCstBench
