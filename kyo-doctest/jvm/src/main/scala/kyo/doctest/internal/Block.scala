package kyo.doctest.internal

import kyo.*

/** A single parsed scala block extracted from a Markdown source file.
  *
  * @param file
  *   The Markdown file that contains this block.
  * @param lineStart
  *   1-indexed line number of the opening backtick code block marker in the source file.
  * @param lineEnd
  *   1-indexed line number of the closing backtick code block marker in the source file.
  * @param body
  *   The raw text between the opening and closing markers, with no surrounding whitespace trimmed.
  * @param visibility
  *   Compilation scope for this block: isolated, inherited, nested, or a named environment.
  * @param expect
  *   What the validator should assert about this block.
  * @param platform
  *   The set of platforms this block should be compiled for.
  * @param carrier
  *   How the block is embedded in the Markdown: visible (plain backtick block) or hidden (HTML comment).
  */
final private[kyo] case class Block(
    file: Path,
    lineStart: Int,
    lineEnd: Int,
    body: String,
    visibility: Block.Visibility,
    expect: Block.Expectation,
    platform: Set[Block.Target],
    carrier: Block.Carrier
) derives CanEqual

private[kyo] object Block:

    /** Visibility axis: what names are in scope when this block is compiled.
      *
      * Isolated (default): the block sees no prior state. Each block is self-contained and copy-pasteable. Inherited: the block sees names
      * introduced by all prior blocks in document order. Nested: prior names are visible but names introduced here do not leak forward.
      * Env(name): all blocks sharing the same name form one cumulative scope.
      */
    enum Visibility derives CanEqual:
        case Isolated
        case Inherited
        case Nested
        case Env(name: String)
    end Visibility

    /** Expectation axis: what the validator should assert about the block.
      *
      * Compiles (default): the block must type-check without error. Runs: the block must type-check and execute without throwing.
      * FailsCompile: the block must produce at least one type error. Warns: the block must compile with at least one warning. Crashes: the
      * block must throw an exception at runtime. Skipped: the block is parsed but not compiled or executed.
      */
    enum Expectation derives CanEqual:
        case Compiles
        case Runs
        case FailsCompile
        case Warns
        case Crashes
        case Skipped
    end Expectation

    /** Platform axis: which scalac target(s) this block should be compiled for.
      *
      * The default is all platforms the project targets. Comma-separated values on the code block info string restrict to the named subset.
      */
    enum Target derives CanEqual:
        case JVM, JS, Native

    /** Carrier: how the block is embedded in the Markdown source.
      *
      * Visible: a plain backtick code block. Reader-visible in the rendered document. Hidden: the block is inside an HTML comment.
      * Hard-hidden: invisible to readers entirely.
      *
      * The carrier controls visibility only. All modifier combinations are valid under any carrier.
      */
    enum Carrier derives CanEqual:
        case Visible
        case Hidden
    end Carrier

end Block
