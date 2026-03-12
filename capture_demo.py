#!/usr/bin/env python3
"""
Capture TUI demo screenshots by running in tmux and rendering ANSI output to PNG.
Uses pyte (terminal emulator) + Pillow (image rendering).
"""
import subprocess, time, os, sys

COLS, ROWS = 80, 24
SESSION = "kyo-ui-demo"
WORKDIR = "/Users/fwbrasil/workspace/kyo/.claude/worktrees/kyo-ui"
OUTPUT_DIR = f"{WORKDIR}/screenshots"
SBT_CMD = 'sbt "kyo-ui / runMain demo.TuiDemo"'

def tmux(*args):
    return subprocess.run(["tmux"] + list(args), capture_output=True, text=True)

def tmux_output(*args):
    r = subprocess.run(["tmux"] + list(args), capture_output=True, text=True)
    return r.stdout

def send_keys(keys, literal=False):
    args = ["send-keys", "-t", SESSION]
    if literal:
        args.append("-l")
    args.append(keys)
    tmux(*args)

def capture_pane_ansi():
    """Capture pane content with ANSI escape codes."""
    r = subprocess.run(
        ["tmux", "capture-pane", "-t", SESSION, "-p", "-e"],
        capture_output=True, text=True
    )
    return r.stdout

def render_ansi_to_png(ansi_text, filename, title=""):
    """Render ANSI text to a PNG image using pyte + Pillow."""
    import pyte
    from PIL import Image, ImageDraw, ImageFont

    # Parse ANSI through pyte terminal emulator
    screen = pyte.Screen(COLS, ROWS)
    stream = pyte.Stream(screen)
    stream.feed(ansi_text)

    # Font setup
    cell_w, cell_h = 10, 20
    padding = 16
    title_h = 30 if title else 0
    img_w = COLS * cell_w + padding * 2
    img_h = ROWS * cell_h + padding * 2 + title_h

    img = Image.new("RGB", (img_w, img_h), (30, 30, 46))  # dark background
    draw = ImageDraw.Draw(img)

    try:
        font = ImageFont.truetype("/System/Library/Fonts/Menlo.ttc", 14)
    except Exception:
        try:
            font = ImageFont.truetype("/System/Library/Fonts/Monaco.ttc", 14)
        except Exception:
            font = ImageFont.load_default()

    # Draw title
    if title:
        draw.text((padding, 8), title, fill=(180, 180, 200), font=font)

    # ANSI color palette
    palette = {
        "black": (0, 0, 0), "red": (239, 68, 68), "green": (34, 197, 94),
        "brown": (234, 179, 8), "blue": (59, 130, 246), "magenta": (168, 85, 247),
        "cyan": (6, 182, 212), "white": (226, 232, 240),
        "default": (205, 214, 244),  # fg default
    }

    def resolve_color(color, is_bg=False):
        if color == "default":
            return (30, 30, 46) if is_bg else (205, 214, 244)
        if isinstance(color, str) and color in palette:
            return palette[color]
        return (205, 214, 244) if not is_bg else (30, 30, 46)

    # Draw each cell
    for row_idx in range(ROWS):
        for col_idx in range(COLS):
            char = screen.buffer[row_idx][col_idx]
            x = padding + col_idx * cell_w
            y = padding + title_h + row_idx * cell_h

            fg_color = resolve_color(char.fg, False)
            bg_color = resolve_color(char.bg, True)

            # Handle reverse video
            if char.reverse:
                fg_color, bg_color = bg_color, fg_color

            # Bold brightens fg
            if char.bold and fg_color == (205, 214, 244):
                fg_color = (255, 255, 255)

            # Draw background
            if bg_color != (30, 30, 46):
                draw.rectangle([x, y, x + cell_w, y + cell_h], fill=bg_color)

            # Draw character
            ch = char.data if char.data != " " else " "
            if ch.strip():
                draw.text((x + 1, y + 1), ch, fill=fg_color, font=font)

            # Underline
            if char.underscore:
                draw.line([(x, y + cell_h - 2), (x + cell_w, y + cell_h - 2)], fill=fg_color)

    os.makedirs(OUTPUT_DIR, exist_ok=True)
    path = f"{OUTPUT_DIR}/{filename}"
    img.save(path)
    print(f"Saved: {path}")
    return path


def main():
    # Kill any existing session
    tmux("kill-session", "-t", SESSION)

    # Start tmux session
    tmux("new-session", "-d", "-s", SESSION, "-x", str(COLS), "-y", str(ROWS))

    # cd to workdir and run sbt
    send_keys(f"cd {WORKDIR}", False)
    send_keys("Enter", False)
    time.sleep(0.5)
    send_keys(SBT_CMD, True)
    send_keys("Enter", False)

    # Wait for sbt to compile and start (this takes a while)
    print("Waiting for sbt to compile and start demo...")
    max_wait = 120
    started = False
    for i in range(max_wait):
        time.sleep(1)
        content = tmux_output("capture-pane", "-t", SESSION, "-p")
        # The TUI app clears the screen and renders, so look for non-sbt content
        if "Kyo UI Demo" in content or "Type here" in content:
            started = True
            print(f"Demo started after {i+1}s")
            time.sleep(1)  # Let it render fully
            break
        if i % 10 == 0 and i > 0:
            print(f"  Still waiting... ({i}s)")

    if not started:
        print("Demo didn't start in time. Capturing current state anyway...")

    # Screenshot 1: Initial state
    ansi = capture_pane_ansi()
    render_ansi_to_png(ansi, "01_initial.png", "Initial State")

    # Interact: Tab to input, type some text
    send_keys("Tab", False)  # Focus input
    time.sleep(0.3)
    send_keys("Hello, Kyo UI!", True)
    time.sleep(0.5)

    # Screenshot 2: After typing
    ansi = capture_pane_ansi()
    render_ansi_to_png(ansi, "02_typed.png", "After Typing")

    # Tab to button and press Enter
    send_keys("Tab", False)
    time.sleep(0.3)

    # Screenshot 3: Button focused
    ansi = capture_pane_ansi()
    render_ansi_to_png(ansi, "03_button_focused.png", "Button Focused")

    send_keys("Enter", False)
    time.sleep(0.5)

    # Screenshot 4: After submit
    ansi = capture_pane_ansi()
    render_ansi_to_png(ansi, "04_after_submit.png", "After Submit")

    # Cleanup
    tmux("kill-session", "-t", SESSION)
    print("\nDone! Screenshots saved to screenshots/")


if __name__ == "__main__":
    main()
