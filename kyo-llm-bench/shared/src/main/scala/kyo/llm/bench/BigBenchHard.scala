// package kyo.llm.bench

// import kyo._
//
// import kyo.files._
// import kyo.llm.ais._
// import kyo.llm.KyoLLMApp
// import kyo.direct._
// import kyo.seqs.Seqs
// import kyo.llm.configs.Config
// import scala.util.matching.Regex

// object BigBenchHard extends KyoLLMApp {

//   val asnwer: Regex = "So the answer is (.*?)\\.".r

//   override def config: Config =
//     super.config.apiKey("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")

//   case class Task(input: String, target: String)

//   case class Bench(name: String, total: Int, successes: Int, percent: Double)

//   run {
//     Seqs.run {
//       for {
//         cotPrompts     <- loadCotPrompts
//         (bench, tasks) <- Seqs.get(loadBenchs)
//         task           <- Seqs.get(tasks)
//         output         <- AIs.ask(cotPrompts(bench), task.input)
//       } yield {
//         bench ->
//           (if (output.takeRight(task.target.size + 5).contains(task.target))
//              1
//            else
//              0)
//       }
//     }.map { r =>
//       r.groupBy(_._1).map {
//         case (name, seq) =>
//           val total     = seq.size
//           val successes = seq.map(_._2).sum
//           Bench(name, total, successes, successes.toDouble / total)
//       }
//     }
//   }

//   def loadBenchs =
//     Seqs.traverse(
//         Files("BIG-Bench-Hard/bbh").readAll("json").map(_.filter(_._1 == "boolean_expressions"))
//     ) {
//       case (name, content) =>
//         case class Examples(examples: List[Task])
//         Json.decode[Examples](content).map { e =>
//           name -> e.examples
//         }
//     }

//   def loadCotPrompts =
//     Files("BIG-Bench-Hard/cot-prompts").readAll("txt").map(_.toMap)

// }
