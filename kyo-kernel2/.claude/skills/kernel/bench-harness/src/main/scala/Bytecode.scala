import Model.*
import kyo.*

/** Reads compiled bytecode with `javap -c -p`.
  *
  * The only rung of the evidence ladder with no statistics to get wrong: no sampling, no warmup, no run at all. It costs about a tenth of a
  * second and it is the source of truth for the byte counts that decide every inlining verdict, since HotSpot's budgets (MaxInlineSize 35,
  * FreqInlineSize 325) are measured against exactly this.
  *
  * It answers the question a size change alone cannot: a method that grew from 31 to 37 bytes crossed a threshold, and knowing *which
  * instructions arrived* is what separates a real cause from a coincidence.
  */
object Bytecode:

    /** One method's compiled form. `size` is the code length in bytes, which is what the JIT's budgets are compared against. */
    case class Method(name: String, signature: String, instructions: Chunk[(Int, String)]) derives Schema:
        /** Offset of the last instruction plus its own length.
          *
          * javap prints offsets but not the code array's length, so the final instruction's width has to be added back. Every JVM method
          * ends in a return or a throw, all of which are one byte, so this is exact rather than an estimate. `verifyAgainst` checks that
          * claim against HotSpot's own declared sizes rather than trusting it.
          */
        def size: Int = instructions.lastMaybe.map(_._1 + 1).getOrElse(0)

        /** Instruction mnemonics in order, which is what a diff compares. */
        def mnemonics: Chunk[String] = instructions.map((_, text) => text.takeWhile(c => !c.isWhitespace))

        def show: String = s"$name$signature ${size}B, ${instructions.size} instructions"
    end Method

    case class Failed(what: String) extends Exception(what) with scala.util.control.NoStackTrace

    type Fail = Abort[Failed | CommandException]

    private val Insn = """^\s+(\d+): (.+)$""".r

    /** A method declaration: two-space indent, ending in `);`.
      *
      * Located by position rather than by a regex over the whole signature. A pattern that tried to match the return type failed on generic
      * ones (`Arrow$Suspend<I, O, E, A, X, S> susp()`, whose angle brackets contain spaces and commas), silently skipped the declaration,
      * and appended that method's instructions to the previous method. The visible symptom was a constructor reporting 15 instructions and a
      * size of 5 bytes. Parameter lists are the only parentheses on these lines, so the first one always opens them.
      */
    private def declaration(line: String): Maybe[(String, String)] =
        if !line.startsWith("  ") || line.startsWith("   ") || !line.endsWith(";") then Maybe.empty
        // a static initializer, which javap prints without a parameter list. Requiring the line to
        // end in `);` skipped these entirely and merged their instructions into the previous
        // method's stream: 19 of 60 classes in the measured tree carry one, and on Arrow$Identity$
        // the constructor was reported as 11 bytes against an actual 5.
        else if line.trim.endsWith("{};") then Maybe(("<clinit>", "()"))
        else
            // `throws` clauses sit after the parameter list, so the signature ends at the matching
            // close paren rather than at the end of the line.
            val open = line.indexOf('(')
            if open < 0 then Maybe.empty
            else
                val close = line.indexOf(')', open)
                if close < 0 then Maybe.empty
                else
                    val before = line.take(open)
                    val name   = before.split("[ .]").lastOption.getOrElse("")
                    if name.isEmpty then Maybe.empty
                    // javap prints a constructor as the fully qualified class name; normalising keeps
                    // it comparable with every other source of method names, which all use <init>
                    else Maybe((if before.contains(".") && !line.contains(" " + name + "(") then "<init>" else name, line.slice(open, close + 1)))

    /** Every method of one class, in declaration order. */
    def of(classpath: Path, className: String)(using Frame): Chunk[Method] < (Async & Fail) =
        Command("javap", "-c", "-p", "-cp", classpath.toString, className).redirectErrorStream(true).textWithExitCode.map { (out, exit) =>
            if exit != ExitCode.Success then Abort.fail(Failed(s"javap failed for $className: $out"))
            else parse(out)
        }

    def parse(raw: String): Chunk[Method] =
        var out     = Chunk.empty[Method]
        var name    = Maybe.empty[(String, String)]
        var insns   = Chunk.empty[(Int, String)]

        def flush(): Unit =
            name.foreach((n, sig) => out = out.append(Method(n, sig, insns)))
            insns = Chunk.empty

        raw.linesIterator.foreach { line =>
            declaration(line) match
                case Maybe.Present(decl) =>
                    flush()
                    name = Maybe(decl)
                case _ =>
                    Insn.findFirstMatchIn(line).foreach(m => insns = insns.append((m.group(1).toInt, m.group(2).trim)))
        }
        flush()
        out
    end parse

    /** What changed between two compilations of one method.
      *
      * Reports the instructions that arrived and left, not two listings side by side: a reader given both listings does the diff by eye and
      * gets it wrong, which is the failure mode this whole harness exists to remove.
      */
    case class Change(method: String, controlSize: Int, variantSize: Int, added: Chunk[String], removed: Chunk[String]) derives Schema:
        def sizeDelta: Int = variantSize - controlSize

        /** Whether the change crossed a HotSpot inlining budget, which is the only reason a size change matters on its own. */
        def crossedBudget: Maybe[String] =
            val budgets = Seq(35 -> "MaxInlineSize", 325 -> "FreqInlineSize")
            Maybe.fromOption(
                budgets.collectFirst {
                    case (limit, nm) if controlSize <= limit && variantSize > limit  => s"grew past $nm ($limit)"
                    case (limit, nm) if controlSize > limit && variantSize <= limit  => s"dropped below $nm ($limit)"
                }
            )

        def show: String =
            val budget = crossedBudget.map(b => s", $b").getOrElse("")
            val plus   = if added.isEmpty then "" else s" +${added.mkString(",")}"
            val minus  = if removed.isEmpty then "" else s" -${removed.mkString(",")}"
            f"$method%s ${controlSize}B -> ${variantSize}B ($sizeDelta%+d)$budget$plus$minus"
    end Change

    /** Methods whose compiled form differs, by name. Methods identical in both are omitted rather than listed as unchanged. */
    def diff(control: Chunk[Method], variant: Chunk[Method]): Chunk[Change] =
        val byName = control.map(m => (m.name + m.signature) -> m).toMap
        Chunk.from(
            variant.flatMap { v =>
                byName.get(v.name + v.signature).flatMap { c =>
                    val cm = c.mnemonics
                    val vm = v.mnemonics
                    if cm == vm && c.size == v.size then None
                    else
                        // multiset difference: an instruction appearing three times in one and twice
                        // in the other is one removal, not zero
                        Some(Change(
                            method = v.name + v.signature,
                            controlSize = c.size,
                            variantSize = v.size,
                            added = Chunk.from(vm.diff(cm).distinct),
                            removed = Chunk.from(cm.diff(vm).distinct)
                        ))
                }
            }
        )
    end diff

    /** Cross-checks computed sizes against HotSpot's own `bytes` attribute from the compilation log.
      *
      * Two independent derivations of the same number. If they disagree, the size arithmetic here is wrong and every budget argument built
      * on it is wrong with it, so this is checked rather than assumed.
      */
    def verifyAgainst(methods: Chunk[Method], declared: Chunk[InlineSites], className: String): Chunk[String] =
        val bySimpleName = methods.groupBy(_.name)
        Chunk.from(
            declared.filter(_.method.startsWith(className)).flatMap { d =>
                val simple = d.method.split("::").lastOption.getOrElse("")
                bySimpleName.get(simple).toSeq.flatten.flatMap { m =>
                    // 0 means the log never declared a size (the unloaded form), which is not a disagreement
                    if d.bytes == 0 || d.bytes == m.size then None
                    else Some(s"${d.method}: javap says ${m.size}B, the compilation log declared ${d.bytes}B")
                }
            }
        )

end Bytecode
