// package kyo

// import kyo.ais._
// import kyo.core._
// import kyo.frames._
// import kyo.consoles._
// import kyo.direct._
// import kyo.requests._
// import kyo.quests._
// import kyo.traits._
// import kyo.aspects._

// import kyo.aspects

// object chat extends KyoApp {

//   def run(args: List[String]) =
//     Requests.run {
//       AIs.run {
//         AIs.init { ai =>
//           Traits.recall(ai) {
//             val cycle =
//               for {
//                 _    <- Consoles.println("************")
//                 _    <- Consoles.println("user: ")
//                 msg  <- Consoles.readln
//                 resp <- ai.ask(msg)
//                 _    <- Consoles.println("assistant: ")
//                 _    <- Consoles.println(resp)
//               } yield ()
//             def loop(): Unit > (Consoles | AIs) =
//               cycle(_ => loop())
//             loop()
//           }
//         }
//       }
//     }
// }
