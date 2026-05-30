package kyo

class YamlVisitorTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    import YamlVisitorTest.*

    "Yaml.visit" - {

        "audits anchors and aliases with typed YAML metadata" in {
            val tag      = Yaml.Tag("!custom")
            val defaults = Yaml.Anchor("defaults")

            assert(tag.value == "!custom")
            assert(defaults.value == "defaults")
            assert((tag match
                case Yaml.Tag(value) => value
            ) == "!custom")
            assert((defaults match
                case Yaml.Anchor(value) => value
            ) == "defaults")

            val yaml =
                """defaults: &defaults
                  |  retries: 3
                  |database: &database
                  |  host: localhost
                  |workers: &workers
                  |  - api
                  |service:
                  |  config: *defaults
                  |  connection: *database
                  |  pool: *workers
                  |orphan: *missing
                  |unused: &unused value
                  |""".stripMargin

            val expected = AnchorAudit(
                declared = Set(Yaml.Anchor("defaults"), Yaml.Anchor("database"), Yaml.Anchor("workers"), Yaml.Anchor("unused")),
                used = Set(Yaml.Anchor("defaults"), Yaml.Anchor("database"), Yaml.Anchor("workers"), Yaml.Anchor("missing")),
                declarationSources = Set(
                    Yaml.Anchor("defaults") -> "mapping",
                    Yaml.Anchor("database") -> "mapping",
                    Yaml.Anchor("workers")  -> "sequence",
                    Yaml.Anchor("unused")   -> "scalar"
                )
            )

            val result = Yaml.visit(yaml, AnchorAudit.empty)(AnchorAuditVisitor)

            assert(result == Result.succeed(expected))
            assert(result.map(_.undeclared) == Result.succeed(Set(Yaml.Anchor("missing"))))
            assert(result.map(_.unused) == Result.succeed(Set(Yaml.Anchor("unused"))))
        }

        "can build a YAML node tree with a typed visitor" in {
            val yaml =
                """profile:
                  |  name: !user &name Alice
                  |  tags: [scala, yaml]
                  |again: *name
                  |""".stripMargin

            val visited: Result[NodeTreeError | DecodeException, Yaml.Node] =
                Yaml.visit(yaml, NodeTreeContext.empty)(NodeTreeVisitor)

            val parsed = Yaml.parse(yaml).getOrThrow

            assert(visited == Result.succeed(parsed))
            parsed match
                case Yaml.Node.Mapping(entries, _) =>
                    assert(entries.size == 2)
                    entries(0) match
                        case (Yaml.Node.Scalar("profile", _), Yaml.Node.Mapping(profileEntries, _)) =>
                            assert(profileEntries.size == 2)
                        case other => fail(s"Expected profile mapping, got $other")
                    end match
                    entries(1) match
                        case (Yaml.Node.Scalar("again", _), Yaml.Node.Alias(Yaml.Anchor("name"), _)) => succeed
                        case other => fail(s"Expected alias entry, got $other")
                    end match
                case other => fail(s"Expected mapping, got $other")
            end match
        }

        "streams block mapping events without requiring a YAML node tree" in {
            val visitor = new Yaml.Visitor[List[String], Nothing, List[String]]:
                def streamStart(context: List[String], mark: Yaml.Mark): Result[Nothing, List[String]] =
                    Result.succeed("streamStart" :: context)

                def documentStart(context: List[String], mark: Yaml.Mark): Result[Nothing, List[String]] =
                    Result.succeed("documentStart" :: context)

                def mappingStart(context: List[String], meta: Yaml.Meta): Result[Nothing, List[String]] =
                    Result.succeed(s"mappingStart:${anchorValue(meta.anchor)}:${tagValue(meta.tag)}" :: context)

                def sequenceStart(context: List[String], meta: Yaml.Meta): Result[Nothing, List[String]] =
                    Result.succeed(s"sequenceStart:${anchorValue(meta.anchor)}:${tagValue(meta.tag)}" :: context)

                def scalar(context: List[String], value: String, meta: Yaml.ScalarMeta): Result[Nothing, List[String]] =
                    Result.succeed(s"scalar:$value:${meta.style}" :: context)

                def alias(context: List[String], name: Yaml.Anchor, mark: Yaml.Mark): Result[Nothing, List[String]] =
                    Result.succeed(s"alias:${name.value}" :: context)

                def nodeEnd(context: List[String], mark: Yaml.Mark): Result[Nothing, List[String]] =
                    Result.succeed("nodeEnd" :: context)

                def documentEnd(context: List[String], mark: Yaml.Mark): Result[Nothing, List[String]] =
                    Result.succeed("documentEnd" :: context)

                def streamEnd(context: List[String], mark: Yaml.Mark): Result[Nothing, List[String]] =
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
            val visitor = new Yaml.Visitor[List[String], Nothing, List[String]]:
                def streamStart(context: List[String], mark: Yaml.Mark): Result[Nothing, List[String]]   = Result.succeed(context)
                def documentStart(context: List[String], mark: Yaml.Mark): Result[Nothing, List[String]] = Result.succeed(context)

                def mappingStart(context: List[String], meta: Yaml.Meta): Result[Nothing, List[String]] =
                    Result.succeed(s"map:${anchorValue(meta.anchor)}:${tagValue(meta.tag)}" :: context)

                def sequenceStart(context: List[String], meta: Yaml.Meta): Result[Nothing, List[String]] =
                    Result.succeed(s"seq:${anchorValue(meta.anchor)}:${tagValue(meta.tag)}" :: context)

                def scalar(context: List[String], value: String, meta: Yaml.ScalarMeta): Result[Nothing, List[String]] =
                    Result.succeed(s"scalar:$value:${anchorValue(meta.anchor)}:${tagValue(meta.tag)}" :: context)

                def alias(context: List[String], name: Yaml.Anchor, mark: Yaml.Mark): Result[Nothing, List[String]] =
                    Result.succeed(s"alias:${name.value}" :: context)

                def nodeEnd(context: List[String], mark: Yaml.Mark): Result[Nothing, List[String]]     = Result.succeed(context)
                def documentEnd(context: List[String], mark: Yaml.Mark): Result[Nothing, List[String]] = Result.succeed(context)
                def streamEnd(context: List[String], mark: Yaml.Mark): Result[Nothing, List[String]]   = Result.succeed(context.reverse)
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
            val visitor = new Yaml.Visitor[List[String], Nothing, List[String]]:
                def streamStart(context: List[String], mark: Yaml.Mark): Result[Nothing, List[String]] =
                    Result.succeed(context)

                def documentStart(context: List[String], mark: Yaml.Mark): Result[Nothing, List[String]] =
                    Result.succeed(context)

                def mappingStart(context: List[String], meta: Yaml.Meta): Result[Nothing, List[String]] =
                    Result.succeed(context)

                def sequenceStart(context: List[String], meta: Yaml.Meta): Result[Nothing, List[String]] =
                    Result.succeed(context)

                def scalar(context: List[String], value: String, meta: Yaml.ScalarMeta): Result[Nothing, List[String]] =
                    Result.succeed(value :: context)

                def alias(context: List[String], name: Yaml.Anchor, mark: Yaml.Mark): Result[Nothing, List[String]] =
                    Result.succeed(context)

                def nodeEnd(context: List[String], mark: Yaml.Mark): Result[Nothing, List[String]] =
                    Result.succeed(context)

                def documentEnd(context: List[String], mark: Yaml.Mark): Result[Nothing, List[String]] =
                    Result.succeed(context)

                def streamEnd(context: List[String], mark: Yaml.Mark): Result[Nothing, List[String]] =
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
end YamlVisitorTest

private object YamlVisitorTest:

    private def anchorValue(anchor: Maybe[Yaml.Anchor]): String =
        anchor.map(_.value).getOrElse("")

    private def tagValue(tag: Maybe[Yaml.Tag]): String =
        tag.map(_.value).getOrElse("")

    final case class AnchorAudit(
        declared: Set[Yaml.Anchor],
        used: Set[Yaml.Anchor],
        declarationSources: Set[(Yaml.Anchor, String)]
    ) derives CanEqual:
        def undeclared: Set[Yaml.Anchor] = used -- declared
        def unused: Set[Yaml.Anchor]     = declared -- used
    end AnchorAudit

    object AnchorAudit:
        val empty: AnchorAudit = AnchorAudit(Set.empty, Set.empty, Set.empty)
    end AnchorAudit

    object AnchorAuditVisitor extends Yaml.Visitor[AnchorAudit, Nothing, AnchorAudit]:
        def streamStart(context: AnchorAudit, mark: Yaml.Mark): Result[Nothing, AnchorAudit] =
            Result.succeed(context)

        def documentStart(context: AnchorAudit, mark: Yaml.Mark): Result[Nothing, AnchorAudit] =
            Result.succeed(context)

        def mappingStart(context: AnchorAudit, meta: Yaml.Meta): Result[Nothing, AnchorAudit] =
            Result.succeed(declare(context, meta.anchor, "mapping"))

        def sequenceStart(context: AnchorAudit, meta: Yaml.Meta): Result[Nothing, AnchorAudit] =
            Result.succeed(declare(context, meta.anchor, "sequence"))

        def scalar(context: AnchorAudit, value: String, meta: Yaml.ScalarMeta): Result[Nothing, AnchorAudit] =
            Result.succeed(declare(context, meta.anchor, "scalar"))

        def alias(context: AnchorAudit, name: Yaml.Anchor, mark: Yaml.Mark): Result[Nothing, AnchorAudit] =
            Result.succeed(context.copy(used = context.used + name))

        def nodeEnd(context: AnchorAudit, mark: Yaml.Mark): Result[Nothing, AnchorAudit] =
            Result.succeed(context)

        def documentEnd(context: AnchorAudit, mark: Yaml.Mark): Result[Nothing, AnchorAudit] =
            Result.succeed(context)

        def streamEnd(context: AnchorAudit, mark: Yaml.Mark): Result[Nothing, AnchorAudit] =
            Result.succeed(context)

        private def declare(context: AnchorAudit, anchor: Maybe[Yaml.Anchor], source: String): AnchorAudit =
            anchor.fold(context)(a =>
                context.copy(
                    declared = context.declared + a,
                    declarationSources = context.declarationSources + (a -> source)
                )
            )
        end declare
    end AnchorAuditVisitor

    enum NodeTreeError derives CanEqual:
        case MappingKeyWithoutValue(key: Yaml.Node)
        case MultipleRootNodes(first: Yaml.Node, second: Yaml.Node)
        case UnexpectedNodeEnd(mark: Yaml.Mark)
        case UnclosedNode(mark: Yaml.Mark)
        case MissingNode(mark: Yaml.Mark)
    end NodeTreeError

    final case class NodeTreeContext(root: Maybe[Yaml.Node], stack: List[BuildFrame]) derives CanEqual

    object NodeTreeContext:
        val empty: NodeTreeContext = NodeTreeContext(Absent, Nil)
    end NodeTreeContext

    sealed trait BuildFrame derives CanEqual

    final case class MappingBuildFrame(
        meta: Yaml.Meta,
        entries: Chunk[(Yaml.Node, Yaml.Node)],
        pendingKey: Maybe[Yaml.Node]
    ) extends BuildFrame

    final case class SequenceBuildFrame(meta: Yaml.Meta, elements: Chunk[Yaml.Node]) extends BuildFrame

    object NodeTreeVisitor extends Yaml.Visitor[NodeTreeContext, NodeTreeError, Yaml.Node]:
        def streamStart(context: NodeTreeContext, mark: Yaml.Mark): Result[NodeTreeError, NodeTreeContext] =
            Result.succeed(context)

        def documentStart(context: NodeTreeContext, mark: Yaml.Mark): Result[NodeTreeError, NodeTreeContext] =
            Result.succeed(context)

        def mappingStart(context: NodeTreeContext, meta: Yaml.Meta): Result[NodeTreeError, NodeTreeContext] =
            Result.succeed(context.copy(stack = MappingBuildFrame(meta, Chunk.empty, Absent) :: context.stack))

        def sequenceStart(context: NodeTreeContext, meta: Yaml.Meta): Result[NodeTreeError, NodeTreeContext] =
            Result.succeed(context.copy(stack = SequenceBuildFrame(meta, Chunk.empty) :: context.stack))

        def scalar(context: NodeTreeContext, value: String, meta: Yaml.ScalarMeta): Result[NodeTreeError, NodeTreeContext] =
            addNode(context, Yaml.Node.Scalar(value, meta))

        def alias(context: NodeTreeContext, name: Yaml.Anchor, mark: Yaml.Mark): Result[NodeTreeError, NodeTreeContext] =
            addNode(context, Yaml.Node.Alias(name, mark))

        def nodeEnd(context: NodeTreeContext, mark: Yaml.Mark): Result[NodeTreeError, NodeTreeContext] =
            context.stack match
                case MappingBuildFrame(meta, entries, pendingKey) :: rest =>
                    pendingKey match
                        case Present(key) => Result.fail(NodeTreeError.MappingKeyWithoutValue(key))
                        case Absent       => addNode(context.copy(stack = rest), Yaml.Node.Mapping(entries, meta))
                    end match
                case SequenceBuildFrame(meta, elements) :: rest =>
                    addNode(context.copy(stack = rest), Yaml.Node.Sequence(elements, meta))
                case Nil =>
                    Result.fail(NodeTreeError.UnexpectedNodeEnd(mark))
            end match
        end nodeEnd

        def documentEnd(context: NodeTreeContext, mark: Yaml.Mark): Result[NodeTreeError, NodeTreeContext] =
            Result.succeed(context)

        def streamEnd(context: NodeTreeContext, mark: Yaml.Mark): Result[NodeTreeError, Yaml.Node] =
            context.root match
                case Present(node) if context.stack.isEmpty => Result.succeed(node)
                case Present(_)                             => Result.fail(NodeTreeError.UnclosedNode(mark))
                case Absent                                 => Result.fail(NodeTreeError.MissingNode(mark))
            end match
        end streamEnd

        private def addNode(context: NodeTreeContext, node: Yaml.Node): Result[NodeTreeError, NodeTreeContext] =
            context.stack match
                case MappingBuildFrame(meta, entries, pendingKey) :: rest =>
                    pendingKey match
                        case Present(key) =>
                            Result.succeed(context.copy(stack = MappingBuildFrame(meta, entries :+ (key -> node), Absent) :: rest))
                        case Absent =>
                            Result.succeed(context.copy(stack = MappingBuildFrame(meta, entries, Maybe(node)) :: rest))
                    end match
                case SequenceBuildFrame(meta, elements) :: rest =>
                    Result.succeed(context.copy(stack = SequenceBuildFrame(meta, elements :+ node) :: rest))
                case Nil =>
                    context.root match
                        case Present(first) => Result.fail(NodeTreeError.MultipleRootNodes(first, node))
                        case Absent         => Result.succeed(context.copy(root = Maybe(node)))
                    end match
            end match
        end addNode
    end NodeTreeVisitor
end YamlVisitorTest
