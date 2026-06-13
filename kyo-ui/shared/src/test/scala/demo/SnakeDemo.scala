package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*
import scala.annotation.tailrec

/** The classic Snake game on the raw SVG layer: a grid of `Svg.rect`s with all state in one `SignalRef[Game]`.
  *
  * Three moving parts, with no glue between them beyond the shared ref:
  *   - A background `Fiber` runs a `Loop` that ticks on a fixed `Async.sleep` cadence, reads the game, advances
  *     it one step (move the head, eat and grow, detect wall/self collisions), and writes it back. It threads a
  *     pure linear-congruential seed (carried in the state) for food placement, so there is no `var`, no
  *     `while`, and no blocked thread.
  *   - `onKeyDown` on the focusable board maps the arrow keys / WASD to a queued turn and Space/Enter to a
  *     restart, by updating the same ref. A turn is rejected if it reverses the snake straight into its own neck.
  *   - The board and the status line `render` off the ref, so each tick redraws the grid reactively.
  *
  * Click the board once to focus it, then steer with the arrow keys (the snake waits for the first key before it
  * starts moving). Run via `sbt 'kyo-uiJVM/Test/runMain demo.Snake'` (optional port as the first argument).
  */
object SnakeDemo extends KyoApp:

    // ---- grid / domain ----

    private val cols = 20
    private val rows = 20
    private val cell = 22 // px per cell

    /** A cell on the grid (column, row). */
    case class Pos(x: Int, y: Int) derives CanEqual

    /** A heading. `delta` is the per-step move; `opposite` is the illegal 180-degree reversal. */
    enum Dir derives CanEqual:
        case Up, Down, Left, Right
        def delta: Pos = this match
            case Up    => Pos(0, -1)
            case Down  => Pos(0, 1)
            case Left  => Pos(-1, 0)
            case Right => Pos(1, 0)
        def opposite: Dir = this match
            case Up    => Down
            case Down  => Up
            case Left  => Right
            case Right => Left
    end Dir

    /** The whole game. `snake` is head-first (index 0 is the head). `dir` is the direction last moved; `queued`
      * is the next direction to apply, validated against `dir` so a fast double-tap cannot reverse into the neck.
      * `running` stays false until the first turn, so the snake waits for input. `seed` is the LCG state used to
      * place food.
      */
    case class Game(snake: Chunk[Pos], dir: Dir, queued: Dir, food: Pos, seed: Long, running: Boolean, dead: Boolean)
        derives CanEqual:
        def score: Int = snake.size - initialLength

        /** Queue a turn from a key press: ignore reversals and dead state; the first accepted turn starts the run. */
        def turn(d: Dir): Game =
            if dead || d == dir.opposite then this
            else copy(queued = d, running = true)
    end Game

    private val initialLength = 3

    /** A length-3 snake at center heading right, with the first food placed off the given seed. */
    private def newGame(seed: Long): Game =
        val cx         = cols / 2
        val cy         = rows / 2
        val body       = Chunk(Pos(cx, cy), Pos(cx - 1, cy), Pos(cx - 2, cy))
        val (s1, food) = spawnFood(seed, body)
        Game(body, Dir.Right, Dir.Right, food, s1, running = false, dead = false)
    end newGame

    // ---- pure RNG (linear congruential) + food placement ----

    /** Advances the LCG seed and returns `(nextSeed, uniformIn[0,1))`. */
    private def nextUnit(seed: Long): (Long, Double) =
        val next = seed * 6364136223846793005L + 1442695040888963407L
        val bits = ((next >>> 40) & 0xffffffL).toDouble
        (next, bits / 0x1000000.toDouble)
    end nextUnit

    /** Pick a random free cell for food, retrying until one is not under the snake; returns the advanced seed. */
    private def spawnFood(seed: Long, occupied: Chunk[Pos]): (Long, Pos) =
        @tailrec def loop(s: Long): (Long, Pos) =
            val (s1, ux) = nextUnit(s)
            val (s2, uy) = nextUnit(s1)
            val p        = Pos((ux * cols).toInt, (uy * rows).toInt)
            if occupied.contains(p) then loop(s2) else (s2, p)
        end loop
        loop(seed)
    end spawnFood

    // ---- one engine step ----

    /** Advance the snake one cell along its queued direction: move the head, eat-and-grow or shift the tail, and
      * flag death on a wall or self collision.
      */
    private def step(g: Game): Game =
        val nd = g.queued
        val nh = Pos(g.snake.head.x + nd.delta.x, g.snake.head.y + nd.delta.y)
        if nh.x < 0 || nh.x >= cols || nh.y < 0 || nh.y >= rows then g.copy(dead = true)
        else
            val eats  = nh == g.food
            val grown = nh +: g.snake                              // prepend the new head
            val body  = if eats then grown else grown.dropRight(1) // drop the tail unless we grew
            if body.tail.contains(nh) then g.copy(dead = true) // ran into our own body
            else if eats then
                val (s1, food1) = spawnFood(g.seed, body)
                g.copy(snake = body, dir = nd, food = food1, seed = s1)
            else g.copy(snake = body, dir = nd)
            end if
        end if
    end step

    /** Map a typed key to a heading, accepting both the arrow keys and WASD. */
    private def dirOf(k: Keyboard): Maybe[Dir] = k match
        case Keyboard.ArrowUp    => Present(Dir.Up)
        case Keyboard.ArrowDown  => Present(Dir.Down)
        case Keyboard.ArrowLeft  => Present(Dir.Left)
        case Keyboard.ArrowRight => Present(Dir.Right)
        case Keyboard.Char(c) =>
            c.toLower match
                case 'w' => Present(Dir.Up)
                case 's' => Present(Dir.Down)
                case 'a' => Present(Dir.Left)
                case 'd' => Present(Dir.Right)
                case _   => Absent
        case _ => Absent

    // ---- colors / styles (dark theme) ----

    private val pageBg    = Style.Color.rgb(15, 18, 28)
    private val boardBg   = Style.Color.rgb(24, 28, 42)
    private val boardEdge = Style.Color.rgb(44, 50, 70)
    private val textCol   = Style.Color.rgb(226, 232, 240)
    private val mutedCol  = Style.Color.rgb(148, 163, 184)
    private val headCol   = Style.Color.rgb(74, 222, 128)
    private val bodyCol   = Style.Color.rgb(34, 197, 94)
    private val foodCol   = Style.Color.rgb(239, 68, 68)

    private val boardPx = cols * cell

    private val pageStyle = Style.column.gap(12.px).padding(20.px).bg(pageBg).color(textCol).fontFamily(_.SansSerif)
    private val statStyle = Style.fontSize(16.px).fontWeight(_.bold)
    private val hintStyle = Style.fontSize(12.px).color(mutedCol)

    // ---- rendering: the whole board is one SVG, redrawn each tick ----

    /** Render the board: a backing rect, the food as a circle, and one square per snake segment (head brighter). */
    private def board(g: Game)(using Frame): Svg.Root =
        val backing =
            Svg.rect.x(0).y(0).width(boardPx).height(boardPx)
                .fill(Svg.Paint.Color(boardBg))
                .stroke(Svg.Paint.Color(boardEdge)).strokeWidth(1.0)
        val food =
            Svg.circle.cx(g.food.x * cell + cell / 2.0).cy(g.food.y * cell + cell / 2.0).r(cell / 2.0 - 3.0)
                .fill(Svg.Paint.Color(foodCol))
        val segments =
            g.snake.zipWithIndex.map { case (p, i) =>
                Svg.rect.x(p.x * cell + 1).y(p.y * cell + 1).width(cell - 2).height(cell - 2)
                    .fill(Svg.Paint.Color(if i == 0 then headCol else bodyCol))
            }
        Svg.svg.width(boardPx).height(boardPx).viewBox(Svg.ViewBox(0, 0, boardPx, boardPx))(
            (backing +: food +: segments)*
        )
    end board

    // ---- app ----

    private[demo] def app: UI < Async =
        for
            state <- Signal.initRef(newGame(0x2545f4914f6cdd1dL))

            // Background engine: advance one step per tick once the game is running and alive.
            _ <- Fiber.initUnscoped {
                Loop(()) { _ =>
                    for
                        g <- state.get
                        _ <- Kyo.when(g.running && !g.dead)(state.set(step(g)))
                        _ <- Async.sleep(120.millis)
                    yield Loop.continue(())
                }
            }
        yield
            // Arrow keys / WASD queue a turn; Space or Enter restarts. All three just update the shared ref.
            val onKey: KeyboardEvent => (Any < Async) = e =>
                if e.key == Keyboard.Space || e.key == Keyboard.Enter then state.updateAndGet(g => newGame(g.seed))
                else
                    dirOf(e.key) match
                        case Present(d) => state.updateAndGet(_.turn(d))
                        case Absent     => ()

            val status =
                state.render { g =>
                    val msg =
                        if g.dead then s"Game over. Score ${g.score}. Press Space or Enter to restart."
                        else if !g.running then "Press an arrow key or WASD to start."
                        else s"Score ${g.score}"
                    UI.div(msg).style(statStyle)
                }

            // `tabIndex(0)` makes the container focusable so it receives key events once clicked.
            UI.div.style(pageStyle).tabIndex(0).onKeyDown(onKey)(
                UI.h2("Snake"),
                state.render(g => UI.div(board(g))),
                status,
                UI.div("Click the board, then steer with the arrow keys or WASD. Space or Enter restarts.").style(hintStyle)
            )
    end app

    run {
        val port = args.headMaybe.flatMap(s => Maybe.fromOption(s.toIntOption)).getOrElse(0)
        for
            handlers <- UI.runHandlers("/")(app)
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"Snake running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end SnakeDemo
