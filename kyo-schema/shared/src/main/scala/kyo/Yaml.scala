package kyo

import java.nio.charset.StandardCharsets

final class Yaml extends Codec:
    def newWriter(): Codec.Writer = kyo.internal.YamlWriter()
    def newReader(input: Span[Byte])(using Frame): Codec.Reader =
        kyo.internal.YamlReader(input)
end Yaml

/** Primary entry point for YAML 1.2 serialization, parsing, and visiting.
  *
  * YAML decoding is event-first: the parser drives a [[Yaml.Visitor]] and does not require constructing a YAML node tree. The schema
  * decoder uses that same event stream and adapts it to kyo-schema's existing codec reader machinery.
  *
  * @see
  *   [[kyo.Json]] for JSON serialization
  * @see
  *   [[kyo.Schema]] for type-driven serialization
  */
object Yaml:
    val DefaultMaxDepth: Int          = Json.DefaultMaxDepth
    val DefaultMaxCollectionSize: Int = Json.DefaultMaxCollectionSize

    given Yaml = Yaml()

    /** Source position in the YAML stream. */
    case class Mark(index: Int, line: Int, column: Int) derives CanEqual

    /** Node metadata carried by collections. */
    case class Meta(anchor: Maybe[String], tag: Maybe[String], mark: Mark) derives CanEqual

    /** Scalar style as written in the YAML stream. */
    enum ScalarStyle derives CanEqual:
        case Plain, SingleQuoted, DoubleQuoted, Literal, Folded
    end ScalarStyle

    /** Metadata carried by scalar nodes. */
    case class ScalarMeta(anchor: Maybe[String], tag: Maybe[String], style: ScalarStyle, mark: Mark) derives CanEqual

    /** Optional YAML node tree built only by [[parse]] and [[parseAll]]. */
    enum Node derives CanEqual:
        case Mapping(entries: Chunk[(Node, Node)], meta: Meta)
        case Sequence(elements: Chunk[Node], meta: Meta)
        case Scalar(value: String, meta: ScalarMeta)
        case Alias(name: String, mark: Mark)
    end Node

    /** Event consumer for YAML parsing.
      *
      * The parser passes caller-owned context through every callback. Returning [[Result.Failure]] stops parsing immediately and returns
      * that error to the caller.
      */
    trait Visitor[Ctx, Err, A]:
        def streamStart(context: Ctx, mark: Mark): Result[Err, Ctx]
        def documentStart(context: Ctx, mark: Mark): Result[Err, Ctx]
        def mappingStart(context: Ctx, meta: Meta): Result[Err, Ctx]
        def sequenceStart(context: Ctx, meta: Meta): Result[Err, Ctx]
        def scalar(context: Ctx, value: String, meta: ScalarMeta): Result[Err, Ctx]
        def alias(context: Ctx, name: String, mark: Mark): Result[Err, Ctx]
        def nodeEnd(context: Ctx, mark: Mark): Result[Err, Ctx]
        def documentEnd(context: Ctx, mark: Mark): Result[Err, Ctx]
        def streamEnd(context: Ctx, mark: Mark): Result[Err, A]
    end Visitor

    /** Visits a YAML stream without constructing a YAML DOM. */
    def visit[Ctx, Err, A](input: String, context: Ctx)(visitor: Visitor[Ctx, Err, A])(using Frame): Result[Err | DecodeException, A] =
        internal.YamlParser(input).visit(context)(visitor)

    /** Parses a single YAML document into an optional DOM node tree. */
    def parse(input: String)(using Frame): Result[DecodeException, Node] =
        internal.YamlParser(input).visit(())(NodeBuilder())

    /** Parses an explicit YAML document stream into node trees. */
    def parseAll(input: String)(using Frame): Result[DecodeException, Chunk[Node]] =
        parseEach(input)(parse)

    /** Encodes a value of type A as YAML. */
    inline def encode[A](value: A)(using schema: Schema[A], frame: Frame): String =
        val w = summon[Yaml].newWriter()
        schema.writeTo(value, w)
        w.resultString
    end encode

    /** Encodes a value of type A as UTF-8 YAML bytes. */
    inline def encodeBytes[A](value: A)(using schema: Schema[A], frame: Frame): Span[Byte] =
        val w = summon[Yaml].newWriter()
        schema.writeTo(value, w)
        w.result()
    end encodeBytes

    /** Decodes a single YAML document into a value of type A. */
    def decode[A](
        input: String,
        maxDepth: Int = DefaultMaxDepth,
        maxCollectionSize: Int = DefaultMaxCollectionSize
    )(using yaml: Yaml, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        decodeBytes(Span.from(input.getBytes(StandardCharsets.UTF_8)), maxDepth, maxCollectionSize)
    end decode

    /** Decodes UTF-8 YAML bytes into a value of type A. */
    def decodeBytes[A](
        input: Span[Byte],
        maxDepth: Int = DefaultMaxDepth,
        maxCollectionSize: Int = DefaultMaxCollectionSize
    )(using yaml: Yaml, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        Result.catching[DecodeException] {
            val reader = yaml.newReader(input)
            reader.resetLimits(maxDepth, maxCollectionSize)
            schema.readFrom(reader)
        }
    end decodeBytes

    /** Decodes every document in an explicit YAML stream. */
    def decodeAll[A](
        input: String,
        maxDepth: Int = DefaultMaxDepth,
        maxCollectionSize: Int = DefaultMaxCollectionSize
    )(using yaml: Yaml, schema: Schema[A], frame: Frame): Result[DecodeException, Chunk[A]] =
        parseEach(input)(doc => decode[A](doc, maxDepth, maxCollectionSize))
    end decodeAll

    private def parseEach[A](input: String)(parseOne: String => Result[DecodeException, A])(using
        Frame
    ): Result[DecodeException, Chunk[A]] =
        val docs = splitDocuments(input)
        def loop(remaining: List[String], acc: Chunk[A]): Result[DecodeException, Chunk[A]] =
            remaining match
                case Nil => Result.succeed(acc)
                case doc :: tail =>
                    parseOne(doc) match
                        case Result.Success(value) => loop(tail, acc :+ value)
                        case Result.Failure(e)     => Result.fail(e)
                        case Result.Panic(e)       => Result.panic(e)
        loop(docs.toList, Chunk.empty)
    end parseEach

    private def splitDocuments(input: String): Chunk[String] =
        val docs    = scala.collection.mutable.ListBuffer.empty[String]
        val current = new StringBuilder
        input.linesIterator.foreach { line =>
            line.trim match
                case directive if directive.startsWith("%") && current.toString.trim.isEmpty =>
                    ()
                case "---" =>
                    if current.toString.trim.nonEmpty then
                        docs += current.toString
                        current.clear()
                case "..." =>
                    if current.toString.trim.nonEmpty then
                        docs += current.toString
                        current.clear()
                case _ =>
                    current.append(line).append('\n')
            end match
        }
        if current.toString.trim.nonEmpty then docs += current.toString
        Chunk.from(docs.toList)
    end splitDocuments

    sealed private trait NodeFrame
    final private class MappingFrame(val meta: Meta, var entries: Chunk[(Node, Node)], var pendingKey: Maybe[Node]) extends NodeFrame
    final private class SequenceFrame(val meta: Meta, var elements: Chunk[Node])                                    extends NodeFrame

    final private class NodeBuilder(using Frame) extends Visitor[Unit, DecodeException, Node]:
        private var root: Maybe[Node]      = Absent
        private var stack: List[NodeFrame] = Nil

        def streamStart(context: Unit, mark: Mark): Result[DecodeException, Unit]   = Result.unit
        def documentStart(context: Unit, mark: Mark): Result[DecodeException, Unit] = Result.unit
        def documentEnd(context: Unit, mark: Mark): Result[DecodeException, Unit]   = Result.unit

        def mappingStart(context: Unit, meta: Meta): Result[DecodeException, Unit] =
            stack = MappingFrame(meta, Chunk.empty, Absent) :: stack
            Result.unit

        def sequenceStart(context: Unit, meta: Meta): Result[DecodeException, Unit] =
            stack = SequenceFrame(meta, Chunk.empty) :: stack
            Result.unit

        def scalar(context: Unit, value: String, meta: ScalarMeta): Result[DecodeException, Unit] =
            addNode(Node.Scalar(value, meta))

        def alias(context: Unit, name: String, mark: Mark): Result[DecodeException, Unit] =
            addNode(Node.Alias(name, mark))

        def nodeEnd(context: Unit, mark: Mark): Result[DecodeException, Unit] =
            stack match
                case (f: MappingFrame) :: rest =>
                    stack = rest
                    addNode(Node.Mapping(f.entries, f.meta))
                case (f: SequenceFrame) :: rest =>
                    stack = rest
                    addNode(Node.Sequence(f.elements, f.meta))
                case Nil =>
                    Result.fail(ParseException(Yaml(), "", "Unexpected YAML node end", Nil, mark.index))
            end match
        end nodeEnd

        def streamEnd(context: Unit, mark: Mark): Result[DecodeException, Node] =
            root match
                case Present(node) => Result.succeed(node)
                case Absent        => Result.fail(ParseException(Yaml(), "", "Expected a YAML document", Nil, mark.index))
        end streamEnd

        private def addNode(node: Node): Result[DecodeException, Unit] =
            stack match
                case (f: MappingFrame) :: _ =>
                    f.pendingKey match
                        case Present(key) =>
                            f.entries = f.entries :+ (key -> node)
                            f.pendingKey = Absent
                        case Absent =>
                            f.pendingKey = Maybe(node)
                    end match
                    Result.unit
                case (f: SequenceFrame) :: _ =>
                    f.elements = f.elements :+ node
                    Result.unit
                case Nil =>
                    root = Maybe(node)
                    Result.unit
            end match
        end addNode
    end NodeBuilder

end Yaml
