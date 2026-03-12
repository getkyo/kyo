package demo

import kyo.*
import kyo.UI.Keyboard
import kyo.UIDsl.*
import scala.language.implicitConversions

object SpaceInvaders extends KyoApp:

    // ---- Constants ----
    private val GW        = 40    // grid width (chars)
    private val GH        = 24    // grid height (rows)
    private val AlienCols = 8
    private val AlienRows = 4
    private val TickMs    = 80
    private val BombRate  = 0.015 // probability per alien per tick

    // ---- Data Types ----
    private case class Pos(x: Int, y: Int) derives CanEqual

    private case class Game(
        player: Int, // x position
        bullets: List[Pos],
        bombs: List[Pos],
        aliens: Set[Pos],
        alienDir: Int, // +1 right, -1 left
        alienShiftDown: Boolean,
        score: Int,
        lives: Int,
        over: Boolean,
        won: Boolean,
        ticks: Int,
        shootCooldown: Int,
        lastAction: Action
    ) derives CanEqual

    private enum Action derives CanEqual:
        case None, Shoot, Hit, PlayerHit, GameOver, Win

    // ---- Sound Engine ----
    private object Sound:
        import javax.sound.sampled.*

        private val SampleRate = 22050f

        private def tone(freqHz: Double, durationMs: Int, volume: Double = 0.3): Array[Byte] =
            val nSamples = (SampleRate * durationMs / 1000).toInt
            val buf      = new Array[Byte](nSamples)
            var i        = 0
            while i < nSamples do
                val angle = 2.0 * Math.PI * i * freqHz / SampleRate
                // Square wave for retro sound
                val sample = if Math.sin(angle) >= 0 then volume else -volume
                buf(i) = (sample * 127).toByte
                i += 1
            end while
            buf
        end tone

        private def noise(durationMs: Int, volume: Double = 0.2): Array[Byte] =
            val nSamples = (SampleRate * durationMs / 1000).toInt
            val buf      = new Array[Byte](nSamples)
            val rng      = java.util.concurrent.ThreadLocalRandom.current()
            var i        = 0
            while i < nSamples do
                buf(i) = (rng.nextGaussian() * volume * 127).toByte
                i += 1
            buf
        end noise

        private def sweep(startHz: Double, endHz: Double, durationMs: Int, volume: Double = 0.3): Array[Byte] =
            val nSamples = (SampleRate * durationMs / 1000).toInt
            val buf      = new Array[Byte](nSamples)
            var i        = 0
            while i < nSamples do
                val t      = i.toDouble / nSamples
                val freq   = startHz + (endHz - startHz) * t
                val angle  = 2.0 * Math.PI * i * freq / SampleRate
                val sample = if Math.sin(angle) >= 0 then volume * (1 - t * 0.5) else -volume * (1 - t * 0.5)
                buf(i) = (sample * 127).toByte
                i += 1
            end while
            buf
        end sweep

        private def play(data: Array[Byte]): Unit =
            val fmt  = new AudioFormat(SampleRate, 8, 1, true, false)
            val info = new DataLine.Info(classOf[Clip], fmt)
            try
                val clip = AudioSystem.getLine(info).asInstanceOf[Clip]
                clip.open(fmt, data, 0, data.length)
                clip.addLineListener { event =>
                    if event.getType.eq(LineEvent.Type.STOP) then
                        clip.close()
                }
                clip.start()
            catch case _: Exception => () // silently ignore sound errors
            end try
        end play

        def shoot(): Unit = play(sweep(880, 1760, 60, 0.15))
        def hit(): Unit   = play(noise(80, 0.3) ++ tone(200, 40, 0.2))
        def bomb(): Unit  = play(sweep(440, 110, 150, 0.2))
        def die(): Unit   = play(sweep(440, 55, 400, 0.3) ++ noise(200, 0.2))
        def win(): Unit   = play(tone(523, 100, 0.2) ++ tone(659, 100, 0.2) ++ tone(784, 200, 0.3))
    end Sound

    // ---- Game Logic ----
    private def newGame(): Game =
        val aliens = (for
            row <- 0 until AlienRows
            col <- 0 until AlienCols
        yield Pos(col * 4 + 4, row * 2 + 2)).toSet
        Game(
            player = GW / 2,
            bullets = Nil,
            bombs = Nil,
            aliens = aliens,
            alienDir = 1,
            alienShiftDown = false,
            score = 0,
            lives = 3,
            over = false,
            won = false,
            ticks = 0,
            shootCooldown = 0,
            lastAction = Action.None
        )
    end newGame

    private def tick(g: Game): Game =
        if g.over then return g

        val rng = java.util.concurrent.ThreadLocalRandom.current()

        // Move bullets up
        val movedBullets = g.bullets.map(b => b.copy(y = b.y - 1)).filter(_.y >= 0)

        // Move bombs down
        val movedBombs = g.bombs.map(b => b.copy(y = b.y + 1)).filter(_.y < GH)

        // Aliens drop bombs
        val newBombs =
            if g.ticks % 3 == 0 then
                g.aliens.toList.filter { a =>
                    rng.nextDouble() < BombRate && !g.aliens.exists(o => o.x == a.x && o.y > a.y)
                }.map(a => Pos(a.x, a.y + 1))
            else Nil

        // Check bullet-alien collisions
        var hitAliens   = Set.empty[Pos]
        var usedBullets = Set.empty[Pos]
        for
            b <- movedBullets
            a <- g.aliens
            if !hitAliens(a) && !usedBullets(b)
            if b.x >= a.x - 1 && b.x <= a.x + 1 && b.y == a.y
        do
            hitAliens += a
            usedBullets += b
        end for

        val remainingBullets = movedBullets.filterNot(usedBullets)
        val remainingAliens  = g.aliens -- hitAliens
        val scoreAdd         = hitAliens.size * 10

        // Check bomb-player collisions
        val playerHit = (movedBombs ++ newBombs).exists { b =>
            b.y == GH - 2 && b.x >= g.player - 1 && b.x <= g.player + 1
        }

        // Move aliens (every 4 ticks, speed up as fewer remain)
        val alienSpeed = math.max(1, 4 - (32 - remainingAliens.size) / 10)
        val (movedAliens, newDir, shiftDown) =
            if g.ticks % alienSpeed == 0 then
                if g.alienShiftDown then
                    // Shift down
                    val shifted = remainingAliens.map(a => a.copy(y = a.y + 1))
                    (shifted, -g.alienDir, false)
                else
                    val moved   = remainingAliens.map(a => a.copy(x = a.x + g.alienDir))
                    val hitEdge = moved.exists(a => a.x <= 1 || a.x >= GW - 2)
                    if hitEdge then
                        (remainingAliens, g.alienDir, true) // don't move, shift down next tick
                    else
                        (moved, g.alienDir, false)
                    end if
            else
                (remainingAliens, g.alienDir, g.alienShiftDown)

        // Check if aliens reached bottom
        val aliensReachedBottom = movedAliens.exists(_.y >= GH - 3)

        // Determine action for sound
        val action =
            if movedAliens.isEmpty then Action.Win
            else if aliensReachedBottom then Action.GameOver
            else if playerHit && g.lives <= 1 then Action.GameOver
            else if playerHit then Action.PlayerHit
            else if hitAliens.nonEmpty then Action.Hit
            else Action.None

        val newLives =
            if playerHit then g.lives - 1
            else g.lives

        val gameOver = movedAliens.isEmpty || aliensReachedBottom || (playerHit && newLives <= 0)

        g.copy(
            bullets = remainingBullets,
            bombs = if playerHit then Nil else movedBombs ++ newBombs,
            aliens = movedAliens,
            alienDir = newDir,
            alienShiftDown = shiftDown,
            score = g.score + scoreAdd,
            lives = newLives,
            over = gameOver,
            won = movedAliens.isEmpty,
            ticks = g.ticks + 1,
            shootCooldown = math.max(0, g.shootCooldown - 1),
            lastAction = action
        )
    end tick

    private def handleKey(g: Game, key: Keyboard): Game =
        if g.over then
            if key == Keyboard.Enter then newGame() else g
        else
            key match
                case Keyboard.ArrowLeft  => g.copy(player = math.max(2, g.player - 2))
                case Keyboard.ArrowRight => g.copy(player = math.min(GW - 3, g.player + 2))
                case Keyboard.Space if g.shootCooldown <= 0 =>
                    g.copy(
                        bullets = Pos(g.player, GH - 3) :: g.bullets,
                        shootCooldown = 3,
                        lastAction = Action.Shoot
                    )
                case _ => g
    end handleKey

    // ---- Sound dispatch (runs on tick) ----
    private def playSound(action: Action): Unit =
        action match
            case Action.Shoot     => Sound.shoot()
            case Action.Hit       => Sound.hit()
            case Action.PlayerHit => Sound.die()
            case Action.GameOver  => Sound.die()
            case Action.Win       => Sound.win()
            case Action.None      =>

    // ---- Rendering ----
    private val bgColor     = Color.rgb(8, 8, 20)
    private val playerColor = Color.rgb(80, 255, 80)
    private val bulletColor = Color.rgb(255, 255, 100)
    private val bombColor   = Color.rgb(255, 80, 80)
    private val starColor   = Color.rgb(60, 60, 80)

    private val alienColors = Array(
        Color.rgb(255, 100, 100), // row 0
        Color.rgb(255, 180, 60),  // row 1
        Color.rgb(100, 200, 255), // row 2
        Color.rgb(200, 100, 255)  // row 3
    )

    // Pre-compute star positions for background
    private val stars: Set[Pos] =
        val rng = new java.util.Random(42)
        (0 until 30).map(_ => Pos(rng.nextInt(GW), rng.nextInt(GH))).toSet

    run {
        for
            state <- Signal.initRef(newGame())
            _ <- Fiber.initUnscoped(
                Loop.forever {
                    for
                        _ <- Async.sleep(TickMs.millis)
                        g <- state.getAndUpdate(tick)
                        _ <- Sync.defer(playSound(g.lastAction))
                    yield ()
                }
            ).unit
            session <- Tui2Backend.render(
                div.style(Style.height(100.pct).padding(0.px, 1.px).bg(bgColor))
                    .onKeyDown { e =>
                        state.getAndUpdate { g =>
                            val g2 = handleKey(g, e.key)
                            if g2.lastAction == Action.Shoot then
                                playSound(Action.Shoot)
                            g2
                        }.unit
                    }(
                        // Title bar
                        nav.style(Style.row.gap(2.px).padding(0.px, 1.px))(
                            span.style(Style.bold.color(Color.rgb(80, 255, 80)))("SPACE INVADERS"),
                            state.map[UI] { g =>
                                fragment(
                                    span.style(Style.color(Color.rgb(255, 255, 100)))(s"Score: ${g.score}"),
                                    span.style(Style.color(Color.rgb(255, 100, 100)))(
                                        "Lives: " + "\u2665 " * g.lives
                                    )
                                )
                            }
                        ),
                        hr,
                        // Game grid
                        state.map[UI] { g =>
                            val alienSet  = g.aliens
                            val bulletSet = g.bullets.toSet
                            val bombSet   = g.bombs.toSet

                            if g.over then
                                div.style(Style.align(Alignment.center).justify(Justification.center).flexGrow(1.0))(
                                    if g.won then
                                        fragment(
                                            h2.style(Style.color(Color.rgb(80, 255, 80)))("YOU WIN!"),
                                            span(s"Final Score: ${g.score}"),
                                            span(""),
                                            span.style(Style.color(Color.rgb(150, 150, 150)))("Press Enter to play again")
                                        )
                                    else
                                        fragment(
                                            h2.style(Style.color(Color.rgb(255, 80, 80)))("GAME OVER"),
                                            span(s"Final Score: ${g.score}"),
                                            span(""),
                                            span.style(Style.color(Color.rgb(150, 150, 150)))("Press Enter to play again")
                                        )
                                )
                            else
                                div(
                                    fragment(
                                        (0 until GH).map { y =>
                                            div.style(Style.row)(
                                                fragment(
                                                    (0 until GW).map { x =>
                                                        val pos = Pos(x, y)
                                                        val (ch, fg, bg) =
                                                            if y == GH - 2 && x >= g.player - 1 && x <= g.player + 1 then
                                                                val c =
                                                                    if x == g.player then "^"
                                                                    else if x == g.player - 1 then "/"
                                                                    else "\\"
                                                                (c, playerColor, bgColor)
                                                            else if bulletSet(pos) then
                                                                ("|", bulletColor, bgColor)
                                                            else if bombSet(pos) then
                                                                ("*", bombColor, bgColor)
                                                            else if alienSet(pos) then
                                                                val row = g.aliens.filter(_ == pos).headOption
                                                                    .map(a => (a.y - 2) / 2)
                                                                    .getOrElse(0)
                                                                val shapes   = Array("W", "M", "X", "H")
                                                                val colorIdx = (row % alienColors.length).max(0).min(alienColors.length - 1)
                                                                (shapes(colorIdx), alienColors(colorIdx), bgColor)
                                                            else if alienSet.exists(a =>
                                                                    (a.x - 1 == x || a.x + 1 == x) && a.y == y
                                                                )
                                                            then
                                                                val a = alienSet.find(a =>
                                                                    (a.x - 1 == x || a.x + 1 == x) && a.y == y
                                                                ).get
                                                                val row      = (a.y - 2) / 2
                                                                val colorIdx = (row % alienColors.length).max(0).min(alienColors.length - 1)
                                                                ("{", alienColors(colorIdx), bgColor)
                                                            else if y == GH - 1 then
                                                                ("-", Color.rgb(40, 40, 60), bgColor)
                                                            else if stars(pos) then
                                                                (".", starColor, bgColor)
                                                            else
                                                                (" ", bgColor, bgColor)
                                                        span.style(Style.color(fg).bg(bg))(ch)
                                                    }*
                                                )
                                            )
                                        }*
                                    )
                                )
                            end if
                        }
                    )
            )
            _ <- session.await
        yield ()
    }

end SpaceInvaders
