// package kyo

// import kyo.ais._
// import kyo.core._
// import kyo.frames._
// import kyo.consoles._
// import kyo.direct._
// import kyo.requests._
// import kyo.quests._
// import kyo.aspects._

// import kyo.aspects
// object aiQuest extends KyoApp {

//   case class Ingredient(name: String, quantity: Int)
//   case class Appliance(name: String)
//   case class Step(
//       description: String,
//       minutes: Int,
//       ingredients: Set[Ingredient],
//       appliances: Set[Appliance]
//   )
//   case class Recipe(name: String, ingredients: Set[Ingredient], steps: List[Step])

//   val quest = defer {
//     val recipe     = await(Quests.select[Recipe]("brazilian recipe"))
//     val ingredient = await(Quests.select[Ingredient]("amido de milho"))
//     await(Quests.filter(recipe.ingredients.contains(ingredient)))
//     val appliance = await(Quests.select[Appliance]("oven"))
//     await(Quests.filter(recipe.steps.flatMap(_.appliances).contains(appliance)))
//     recipe
//   }

//   def run(args: List[String]) =
//     Requests.run {
//       AIs.run {
//         AIs.init { ai =>
//           Traits.introspection(ai) {
//             Quests.run(ai)(quest) { r =>
//               Consoles.println(ai.dump(_ + "\n" + r))
//             }.unit
//           }
//         }
//       }
//     }
// }
