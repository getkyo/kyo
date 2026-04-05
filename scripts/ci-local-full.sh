#!/usr/bin/env bash
set -euo pipefail

# Run the full 18-job CI matrix locally.
#
# Usage:
#   ./scripts/ci-local-full.sh              # run all 18 jobs
#   ./scripts/ci-local-full.sh linux        # run linux jobs only (6)
#   ./scripts/ci-local-full.sh macos        # run macos jobs only (6)
#   ./scripts/ci-local-full.sh windows      # run windows jobs only (6)
#   ./scripts/ci-local-full.sh JVM          # run all JVM jobs (6)
#   ./scripts/ci-local-full.sh linux JVM    # run linux JVM jobs only (2)
#
# Platforms:
#   - Linux:   Docker containers via `act` (reads build.yml directly)
#   - macOS:   `act -self-hosted` on this machine (reads build.yml directly)
#   - Windows: UTM VM via SSH (replicates build.yml test step)
#
# First-time setup is automatic (installs act, pulls images, guides UTM setup).

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# ─── Configuration ───────────────────────────────────────────────────────────

DOCKER_IMAGE="catthehacker/ubuntu:act-latest"
WINDOWS_VM_NAME="kyo-windows"
WINDOWS_SSH_PORT=2222
WINDOWS_SSH_USER="${KYO_WINDOWS_USER:-kyo}"

# ─── Dependency checks (gated by what's needed) ─────────────────────────────

need_podman() {
    if ! command -v podman &>/dev/null; then
        echo "Installing podman..."
        brew install podman
    fi
    if ! podman info &>/dev/null 2>&1; then
        echo "Starting podman machine..."
        podman machine start 2>/dev/null || true
    fi
}

# Podman socket for act (Docker-compatible API)
get_podman_socket() {
    podman machine inspect 2>/dev/null \
        | grep -o '"Path": "[^"]*api.sock"' \
        | head -1 \
        | sed 's/"Path": "//;s/"//'
}

need_act() {
    if ! command -v act &>/dev/null; then
        echo "Installing act (GitHub Actions local runner)..."
        brew install act
    fi
    # Ensure act image is available via podman
    if ! podman image exists "$DOCKER_IMAGE" 2>/dev/null; then
        echo "Pulling $DOCKER_IMAGE (first run only, ~1.6GB)..."
        podman pull "$DOCKER_IMAGE"
    fi
}

need_windows_vm() {
    if ! command -v utmctl &>/dev/null; then
        echo ""
        echo "═══════════════════════════════════════════════════════════"
        echo "  Windows VM required (UTM not installed)"
        echo "═══════════════════════════════════════════════════════════"
        echo ""
        echo "  1. Install UTM:  brew install --cask utm"
        echo "  2. Download Windows 11 ARM64 ISO from Microsoft:"
        echo "     https://www.microsoft.com/en-us/software-download/windows11arm64"
        echo "  3. Create VM named '$WINDOWS_VM_NAME' in UTM:"
        echo "     - 8GB RAM, 4 cores, 80GB disk"
        echo "     - Enable SSH: Settings → System → Optional Features → OpenSSH Server"
        echo "     - Port forward: 2222 → 22 (in UTM network settings)"
        echo "  4. Inside the VM, install (PowerShell as Admin):"
        echo "     winget install Amazon.Corretto.25"
        echo "     winget install sbt.sbt"
        echo "     winget install Git.Git"
        echo "     winget install OpenJS.NodeJS"
        echo "     winget install LLVM.LLVM"
        echo "  5. Clone the repo: git clone https://github.com/getkyo/kyo.git C:\\kyo"
        echo "  6. Test SSH: ssh -p $WINDOWS_SSH_PORT $WINDOWS_SSH_USER@localhost 'sbt --version'"
        echo ""
        exit 1
    fi
    if ! utmctl list 2>/dev/null | grep -q "$WINDOWS_VM_NAME"; then
        echo "Error: UTM VM '$WINDOWS_VM_NAME' not found."
        echo "Create a Windows ARM64 VM named '$WINDOWS_VM_NAME' in UTM."
        echo "See setup instructions: ./scripts/ci-local-full.sh --help"
        exit 1
    fi
    # Start VM if not running
    if ! utmctl status "$WINDOWS_VM_NAME" 2>/dev/null | grep -qi "started"; then
        echo "Starting Windows VM..."
        utmctl start "$WINDOWS_VM_NAME"
        echo "Waiting for SSH..."
        for i in $(seq 1 60); do
            if ssh -p "$WINDOWS_SSH_PORT" -o ConnectTimeout=2 -o StrictHostKeyChecking=no \
                "$WINDOWS_SSH_USER@localhost" "echo ok" &>/dev/null; then
                break
            fi
            sleep 2
        done
    fi
    # Verify SSH
    if ! ssh -p "$WINDOWS_SSH_PORT" -o ConnectTimeout=5 -o StrictHostKeyChecking=no \
        "$WINDOWS_SSH_USER@localhost" "echo ok" &>/dev/null; then
        echo "Error: Cannot SSH to Windows VM at localhost:$WINDOWS_SSH_PORT"
        exit 1
    fi
}

# ─── act wrapper (workaround for Docker credential helper hang) ──────────────

ACT_DOCKER_CONFIG="$HOME/.docker/act-config"
mkdir -p "$ACT_DOCKER_CONFIG"
if [ ! -f "$ACT_DOCKER_CONFIG/config.json" ]; then
    echo '{"auths":{}}' > "$ACT_DOCKER_CONFIG/config.json"
fi

run_linux() {
    local os="$1" target="$2"

    echo ""
    echo "════════════════════════════════════════════════════"
    echo "  Running: $os / $target (via podman)"
    echo "════════════════════════════════════════════════════"
    echo ""

    local platform="linux/arm64"
    if [[ "$os" == "linux-x64" ]]; then
        platform="linux/amd64"
    fi

    # Mirrors build.yml test step: testKyo --all <target>
    # sbt-linux.sh handles JDK install, native deps, sbt setup in a
    # fresh container matching CI (ubuntu:noble + Corretto JDK).
    local cmd="testKyo --all $target"
    if [ "$target" = "Native" ]; then
        PLATFORM="$platform" "$SCRIPT_DIR/sbt-linux.sh" "$cmd"
    else
        PLATFORM="$platform" "$SCRIPT_DIR/sbt-linux.sh" "$cmd"
    fi
}

run_act() {
    local os="$1" target="$2"

    echo ""
    echo "════════════════════════════════════════════════════"
    echo "  Running: $os / $target (via act)"
    echo "════════════════════════════════════════════════════"
    echo ""

    local podman_sock
    podman_sock="$(get_podman_socket)"

    DOCKER_HOST="unix://$podman_sock" \
    DOCKER_CONFIG="$ACT_DOCKER_CONFIG" \
    act pull_request \
        -W .github/workflows/build-pr.yml \
        --pull=false \
        --container-daemon-socket - \
        -P "build=$DOCKER_IMAGE" \
        -P "macos-latest-large=-self-hosted" \
        -P "macos-latest-xlarge=-self-hosted" \
        --matrix "os:$os" \
        --matrix "target:$target"
}

# ─── Windows runner (SSH to UTM VM) ─────────────────────────────────────────

run_windows() {
    local os="$1" target="$2"

    echo ""
    echo "════════════════════════════════════════════════════"
    echo "  Running: $os / $target (via Windows VM SSH)"
    echo "════════════════════════════════════════════════════"
    echo ""

    # Sync code to VM
    echo "Syncing code to Windows VM..."
    rsync -az --delete \
        -e "ssh -p $WINDOWS_SSH_PORT -o StrictHostKeyChecking=no" \
        --exclude='.git' --exclude='target' --exclude='node_modules' \
        "$PROJECT_DIR/" "$WINDOWS_SSH_USER@localhost:C:/kyo/"

    # Build the test command (mirrors build.yml)
    local cmd="set SBT_TASK_LIMIT=1 && cd C:\\kyo && sbt \"testKyo $target\""
    if [ "$target" = "Native" ]; then
        cmd="set SBT_TASK_LIMIT=1 && cd C:\\kyo && bash scripts/native-test.sh \"testKyo $target\""
    fi

    ssh -p "$WINDOWS_SSH_PORT" -o StrictHostKeyChecking=no \
        "$WINDOWS_SSH_USER@localhost" "$cmd"
}

# ─── Matrix definition ──────────────────────────────────────────────────────

ALL_OS=(linux-x64 linux-arm64 macos-x64 macos-arm64 windows-x64 windows-arm64)
ALL_TARGETS=(JVM JS Native)

# ─── Parse arguments ────────────────────────────────────────────────────────

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
    echo "Usage: $0 [os-filter] [target-filter]"
    echo ""
    echo "OS filters:    linux, macos, windows, linux-x64, macos-arm64, etc."
    echo "Target filters: JVM, JS, Native"
    echo ""
    echo "Examples:"
    echo "  $0                     # all 18 jobs"
    echo "  $0 linux               # 6 linux jobs"
    echo "  $0 macos JVM           # 2 macOS JVM jobs"
    echo "  $0 windows-arm64 Native  # 1 specific job"
    exit 0
fi

OS_FILTER="${1:-}"
TARGET_FILTER="${2:-}"

# ─── Determine which jobs to run ────────────────────────────────────────────

JOBS=()
for os in "${ALL_OS[@]}"; do
    if [ -n "$OS_FILTER" ] && [[ ! "$os" == *"$OS_FILTER"* ]]; then
        continue
    fi
    for target in "${ALL_TARGETS[@]}"; do
        if [ -n "$TARGET_FILTER" ] && [ "$target" != "$TARGET_FILTER" ]; then
            continue
        fi
        JOBS+=("$os:$target")
    done
done

if [ ${#JOBS[@]} -eq 0 ]; then
    echo "No jobs match filter: os='$OS_FILTER' target='$TARGET_FILTER'"
    exit 1
fi

echo "Jobs to run (${#JOBS[@]}):"
for job in "${JOBS[@]}"; do echo "  $job"; done
echo ""

# ─── Check dependencies based on what we need ───────────────────────────────

needs_linux=false
needs_macos=false
needs_windows=false

for job in "${JOBS[@]}"; do
    case "$job" in
        linux-*)   needs_linux=true ;;
        macos-*)   needs_macos=true ;;
        windows-*) needs_windows=true ;;
    esac
done

if $needs_linux || $needs_macos; then
    need_podman
fi
if $needs_macos; then
    need_act
fi
if $needs_windows; then
    need_windows_vm
fi

# ─── Run jobs ───────────────────────────────────────────────────────────────

PASSED=()
FAILED=()

for job in "${JOBS[@]}"; do
    os="${job%%:*}"
    target="${job##*:}"

    if [[ "$os" == linux-* ]]; then
        if run_linux "$os" "$target"; then
            PASSED+=("$job")
        else
            FAILED+=("$job")
        fi
    elif [[ "$os" == windows-* ]]; then
        if run_windows "$os" "$target"; then
            PASSED+=("$job")
        else
            FAILED+=("$job")
        fi
    else
        if run_act "$os" "$target"; then
            PASSED+=("$job")
        else
            FAILED+=("$job")
        fi
    fi
done

# ─── Summary ────────────────────────────────────────────────────────────────

echo ""
echo "════════════════════════════════════════════════════"
echo "  Results: ${#PASSED[@]} passed, ${#FAILED[@]} failed"
echo "════════════════════════════════════════════════════"

if [ ${#PASSED[@]} -gt 0 ]; then
    echo ""
    echo "  ✅ Passed:"
    for job in "${PASSED[@]}"; do echo "     $job"; done
fi

if [ ${#FAILED[@]} -gt 0 ]; then
    echo ""
    echo "  ❌ Failed:"
    for job in "${FAILED[@]}"; do echo "     $job"; done
    echo ""
    exit 1
fi

echo ""
echo "  All ${#JOBS[@]} jobs passed! Safe to push."
