package kyo.internal.tui

import kyo.*
import kyo.Length.*
import kyo.internal.tui.pipeline.*
import scala.language.implicitConversions

/** Headless profiling test: runs the snake game render loop for N iterations and measures per-frame time. No terminal I/O — uses
  * Pipeline.renderFrame directly.
  */
class SnakeProfileTest extends kyo.Test:
    import AllowUnsafe.embrace.danger
    import kyo.UI.render as uiRender

    case class SnakeGame(
        snake: Chunk[(Int, Int)],
        food: (Int, Int),
        dir: (Int, Int),
        score: Int,
        alive: Boolean
    ) derives CanEqual

    "snake render loop profile" in run {
        for
            gameRef <- Signal.initRef(SnakeGame(
                snake = Chunk((5, 5), (4, 5), (3, 5)),
                food = (10, 8),
                dir = (1, 0),
                score = 0,
                alive = true
            ))
        yield
            val state    = new ScreenState(ResolvedTheme.resolve(Theme.Default))
            val viewport = Rect(0, 0, 40, 20)

            def step(): Unit =
                val g       = gameRef.unsafe.get()
                val head    = g.snake(0)
                val newHead = ((head._1 + g.dir._1 + 20) % 20, (head._2 + g.dir._2 + 12) % 12)
                val ate     = newHead == g.food
                val newSnake =
                    if ate then Chunk(newHead).concat(g.snake)
                    else Chunk(newHead).concat(g.snake.take(g.snake.size - 1))
                val newFood  = if ate then ((g.food._1 + 7) % 20, (g.food._2 + 3) % 12) else g.food
                val newScore = if ate then g.score + 1 else g.score
                gameRef.unsafe.set(g.copy(snake = newSnake, food = newFood, score = newScore))
            end step

            val ui = gameRef.uiRender { g =>
                val grid = (0 until 12).map { y =>
                    val row = (0 until 20).map { x =>
                        if g.snake.exists(_ == (x, y)) then "█"
                        else if g.food == (x, y) then "●"
                        else "·"
                    }.mkString
                    UI.div(row): UI
                }
                UI.div(UI.div(s"Score: ${g.score}"), UI.div(grid*))
            }

            // Warmup
            val warmupFrames = 5
            var i            = 0
            while i < warmupFrames do
                step()
                Pipeline.renderFrame(ui, state, viewport)
                i += 1
            end while

            // Profile
            val frames   = 50
            val times    = new Array[Long](frames)
            var frame    = 0
            val startAll = java.lang.System.nanoTime()
            while frame < frames do
                val t0 = java.lang.System.nanoTime()
                step()
                Pipeline.renderFrame(ui, state, viewport)
                times(frame) = java.lang.System.nanoTime() - t0
                frame += 1
            end while
            val totalMs = (java.lang.System.nanoTime() - startAll) / 1_000_000

            val avgMs = times.map(_ / 1_000_000).sum.toDouble / frames
            val maxMs = times.map(_ / 1_000_000).max
            val minMs = times.map(_ / 1_000_000).min

            java.lang.System.err.println(s"\n=== Snake Profile: $frames frames ===")
            java.lang.System.err.println(s"Total: ${totalMs}ms")
            java.lang.System.err.println(s"Avg: ${avgMs}ms/frame")
            java.lang.System.err.println(s"Min: ${minMs}ms, Max: ${maxMs}ms")
            java.lang.System.err.println(s"FPS: ${1000.0 / avgMs}")

            // Assert reasonable performance: each frame should be under 50ms
            assert(avgMs < 50, s"Average frame time ${avgMs}ms is too slow (>50ms)")
            succeed
        end for
    }
end SnakeProfileTest
