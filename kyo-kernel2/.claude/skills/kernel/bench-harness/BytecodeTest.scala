import Model.*
import kyo.*

/** Holds the bytecode reader to real compiled classes.
  *
  * Its whole value is that its numbers are exact, so the arithmetic producing them is checked rather than asserted. `size` adds one byte
  * back for the final instruction, which is only right if every method ends in a one-byte return or throw. That is checked here directly
  * instead of being left as a comment nobody re-derives.
  */
object BytecodeTest extends KyoApp:

    val classpath = Path(
        "/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/.bloop/kyo-kernel2JVM/bloop-bsp-clients-classes/classes-Metals-HW3GXRmfQLCsg2UoVIDngA=="
    )

    /** Every JVM method ends in one of these, and each is a single byte. */
    val Terminators = Set("return", "areturn", "ireturn", "lreturn", "freturn", "dreturn", "athrow")

    var failures = 0

    def check(name: String, cond: Boolean, detail: => String = ""): Unit =
        if cond then println(s"  ok   $name")
        else
            failures += 1
            println(s"  FAIL $name${if detail.nonEmpty then s"\n         $detail" else ""}")

    run {
        for
            methods <- Bytecode.of(classpath, "kyo.kernel.proto.Arrow$SuspendWith")

            _ = println("\nreading a real class")
            _ = check("methods found", methods.nonEmpty, s"got ${methods.size}")
            _ = check("the constructor is present", methods.exists(_.name == "<init>"), methods.map(_.name).mkString(","))
            _ = check("apply is present", methods.exists(_.name == "apply"), methods.map(_.name).mkString(","))
            _ = check("instructions carry offsets", methods.forall(m => m.instructions.isEmpty || m.instructions.head._1 == 0))

            _ = println("\nsize arithmetic")
            // the load-bearing assumption behind every budget argument this tool makes
            nonTerminated = methods.filter(m => m.instructions.nonEmpty && !Terminators.contains(m.mnemonics.last))
            _ = check(
                "every method ends in a one-byte return or throw, so last offset + 1 is exact",
                nonTerminated.isEmpty,
                nonTerminated.map(m => s"${m.name} ends in '${m.mnemonics.last}'").mkString("; ")
            )
            _ = check("sizes are positive", methods.filter(_.instructions.nonEmpty).forall(_.size > 0))
            _ = methods.foreach(m => println(s"       ${m.show}"))

            _ = println("\ndeclarations javap prints in other shapes")
            // 19 of 60 classes in the measured tree carry a static initializer, which was skipped
            // entirely, merging its instruction stream into the previous method. On this class the
            // constructor was reported as 11B against an actual 5B.
            identity <- Bytecode.of(classpath, "kyo.kernel.proto.Arrow$Identity$")
            ctor = identity.find(_.name == "<init>")
            _ = check("the static initializer is a method of its own", identity.exists(_.name == "<clinit>"), identity.map(_.name).mkString(","))
            _ = check("so the constructor keeps only its own instructions", ctor.exists(_.size <= 6), s"ctor is ${ctor.map(_.show).getOrElse("absent")}")
            _ = identity.foreach(m => println(s"       ${m.show}"))
            // a throws clause puts the signature's close paren before the end of the line
            withThrows = Bytecode.parse("""  public void read() throws java.io.IOException;
    Code:
       0: return
  public int size();
    Code:
       0: iconst_0
       1: ireturn
""")
            _ = check("a declaration with a throws clause is not skipped", withThrows.exists(_.name == "read"), withThrows.map(_.name).mkString(","))
            _ = check("and does not absorb the next method", withThrows.find(_.name == "read").exists(_.instructions.size == 1), withThrows.map(_.show).mkString(" | "))

            _ = println("\ndiffing")
            // a method that lost an instruction must report it, and one that did not must not appear
            trimmed = methods.map(m =>
                if m.name == "apply" then m.copy(instructions = m.instructions.dropRight(1)) else m
            )
            changes = Bytecode.diff(methods, trimmed)
            _ = check("only the changed method is reported", changes.size == 1, changes.map(_.method).mkString(","))
            _ = check("the change names the method", changes.headMaybe.exists(_.method.startsWith("apply")), changes.map(_.show).mkString)
            _ = check("an unchanged class diffs to nothing", Bytecode.diff(methods, methods).isEmpty)

            _ = println("\nbudget crossing")
            // the only reason a size change matters on its own
            grew = Bytecode.Change("m()", 31, 37, Chunk("invokevirtual"), Chunk.empty)
            same = Bytecode.Change("m()", 12, 18, Chunk("aload_0"), Chunk.empty)
            _ = check("crossing MaxInlineSize is called out", grew.crossedBudget.exists(_.contains("MaxInlineSize")), s"${grew.crossedBudget}")
            _ = check("growth inside a budget is not", same.crossedBudget.isEmpty, s"${same.crossedBudget}")
            _ = check("the rendering carries the instructions", grew.show.contains("+invokevirtual"), grew.show)

            _ <- Console.printLine(if failures == 0 then "\nall bytecode checks passed\n" else s"\n$failures bytecode check(s) failed\n")
            _ <- Abort.when(failures > 0)(Bytecode.Failed(s"$failures bytecode check(s) failed"))
        yield ()
        end for
    }
end BytecodeTest
