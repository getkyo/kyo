package demo

import kyo.*
import kyo.UI.Keyboard
import kyo.UIDsl.*
import scala.language.implicitConversions

object TuiDemo extends KyoApp:

    private val GW = 20
    private val GH = 12

    private enum Dir derives CanEqual:
        case Up, Down, Left, Right

    private case class Pos(x: Int, y: Int) derives CanEqual

    private case class Game(
        snake: List[Pos],
        food: Pos,
        dir: Dir,
        score: Int,
        over: Boolean
    ) derives CanEqual

    private def rndFood(taken: Set[Pos]): Pos =
        val r = java.util.concurrent.ThreadLocalRandom.current()
        var p = Pos(r.nextInt(GW), r.nextInt(GH))
        while taken(p) do p = Pos(r.nextInt(GW), r.nextInt(GH))
        p
    end rndFood

    private def newGame(): Game =
        val s = List(Pos(GW / 2, GH / 2), Pos(GW / 2 - 1, GH / 2), Pos(GW / 2 - 2, GH / 2))
        Game(s, rndFood(s.toSet), Dir.Right, 0, false)

    private def step(p: Pos, d: Dir): Pos = d match
        case Dir.Up    => Pos(p.x, p.y - 1)
        case Dir.Down  => Pos(p.x, p.y + 1)
        case Dir.Left  => Pos(p.x - 1, p.y)
        case Dir.Right => Pos(p.x + 1, p.y)

    private def opp(d: Dir): Dir = d match
        case Dir.Up    => Dir.Down
        case Dir.Down  => Dir.Up
        case Dir.Left  => Dir.Right
        case Dir.Right => Dir.Left

    private def inBounds(p: Pos): Boolean =
        p.x >= 0 && p.x < GW && p.y >= 0 && p.y < GH

    private def tick(g: Game): Game =
        if g.over then g
        else
            val nh = step(g.snake.head, g.dir)
            if !inBounds(nh) || g.snake.toSet(nh) then
                g.copy(over = true)
            else if nh == g.food then
                val ns = nh :: g.snake
                Game(ns, rndFood(ns.toSet), g.dir, g.score + 1, false)
            else
                g.copy(snake = nh :: g.snake.init)
            end if

    run {
        for
            state <- Signal.initRef(newGame())
            _ <- Fiber.initUnscoped(
                Loop.forever {
                    for
                        _ <- Async.sleep(150.millis)
                        _ <- state.getAndUpdate(tick)
                    yield ()
                }
            ).unit
            session <- TuiBackend.render(
                div.style(Style.height(100.pct).padding(1.px, 2.px))
                    .onKeyDown { e =>
                        state.getAndUpdate { g =>
                            if g.over then
                                if e.key == Keyboard.Enter then newGame() else g
                            else
                                e.key match
                                    case Keyboard.ArrowUp if g.dir != Dir.Down    => g.copy(dir = Dir.Up)
                                    case Keyboard.ArrowDown if g.dir != Dir.Up    => g.copy(dir = Dir.Down)
                                    case Keyboard.ArrowLeft if g.dir != Dir.Right => g.copy(dir = Dir.Left)
                                    case Keyboard.ArrowRight if g.dir != Dir.Left => g.copy(dir = Dir.Right)
                                    case _                                        => g
                        }.unit
                    }(
                        h3("SNAKE"),
                        state.map[UI] { g =>
                            val sm  = g.snake.zipWithIndex.toMap
                            val len = g.snake.length.max(1)
                            fragment(
                                nav.style(Style.gap(4.px))(
                                    span(s"Score: ${g.score}"),
                                    if g.over then
                                        span.style(Style.color(Color.red).bold)("GAME OVER - press Enter")
                                    else span("")
                                ),
                                div(
                                    fragment(
                                        (0 until GH).map { y =>
                                            div.style(Style.row)(
                                                fragment(
                                                    (0 until GW).map { x =>
                                                        val p = Pos(x, y)
                                                        val bg = sm.get(p) match
                                                            case Some(0) =>
                                                                if g.over then Color.rgb(200, 60, 60)
                                                                else Color.rgb(140, 255, 160)
                                                            case Some(i) =>
                                                                val t = i.toDouble / len
                                                                if g.over then Color.rgb((160 * (1 - t * 0.4)).toInt, 50, 50)
                                                                else Color.rgb(0, (200 - (100 * t)).toInt, (90 - (40 * t)).toInt)
                                                            case None if p == g.food =>
                                                                Color.rgb(255, 80, 60)
                                                            case None =>
                                                                Color.rgb(22, 22, 38)
                                                        span.style(Style.bg(bg))("  ")
                                                    }*
                                                )
                                            )
                                        }*
                                    )
                                )
                            )
                        }
                    )
            )
            _ <- session.await
        yield ()
    }

end TuiDemo
