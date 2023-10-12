package kyo.chatgpt

import kyo._
import consoles._
import ais._
import contexts._
import requests._
import plugins._
import kyo.App.Effects
import plugins.BraveSearch

object test extends App {

  case class Actor(id: Int, name: String, character: String)

  case class Episode(season: Int, episodeNumber: Int, title: String, airDate: Option[String])

  case class TVSeries(
      id: Int,
      title: String,
      releaseYear: Int,
      genre: List[String],
      actors: List[Actor],
      episodes: List[Episode]
  )

  def run(args: List[String]) =
    Requests.run {
      AIs.run {
        BraveSearch.ApiKey.let("BSAM1SDyi5fcPGqqrqKLWzj6dTJMx32") {
          AIs.ApiKey.let("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") {
            Consoles.println(
                Plugins.enable(BraveSearch.plugin, HttpGet.plugin) {
                  AIs.init.map(_.infer[TVSeries](
                      "Stranger Things"
                  ))
                }
            )
          }
        }
      }
    }
}
