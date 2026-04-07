#!/usr/bin/env bash
set -euo pipefail

# Run GitHub Actions workflows locally using `act`.
#
# Usage:
#   ./scripts/ci-local.sh                  # list available workflows
#   ./scripts/ci-local.sh pr               # run PR build (testDiff, linux)
#   ./scripts/ci-local.sh main             # run main build (full test, all OSes)
#   ./scripts/ci-local.sh scalafmt         # run scalafmt check
#   ./scripts/ci-local.sh readme           # run readme check
#   ./scripts/ci-local.sh lint             # run actionlint
#   ./scripts/ci-local.sh <workflow> [act flags...]

if ! command -v act &>/dev/null; then
    echo "Error: 'act' is not installed."
    echo ""
    echo "'act' runs GitHub Actions workflows locally using Docker."
    echo "See: https://github.com/nektos/act"
    echo ""
    echo "Install:"
    echo "  macOS:   brew install act"
    echo "  Linux:   curl -s https://raw.githubusercontent.com/nektos/act/master/install.sh | sudo bash"
    echo "  Windows: choco install act-cli"
    echo ""
    echo "Requires Docker to be installed and running."
    exit 1
fi

if ! docker info &>/dev/null 2>&1; then
    echo "Error: Docker is not running."
    echo ""
    echo "Start Docker Desktop or the Docker daemon, then try again."
    exit 1
fi

# Workaround: act hangs when Docker Desktop's credential helper (credsStore: "desktop")
# is configured. Provide a clean Docker config to avoid the hang.
# See: https://github.com/nektos/act/issues/1949
ACT_DOCKER_CONFIG="$HOME/.docker/act-config"
mkdir -p "$ACT_DOCKER_CONFIG"
if [ ! -f "$ACT_DOCKER_CONFIG/config.json" ]; then
    echo '{"auths":{}}' > "$ACT_DOCKER_CONFIG/config.json"
fi
export DOCKER_CONFIG="$ACT_DOCKER_CONFIG"

IMAGE="catthehacker/ubuntu:act-latest"

# Ensure the Docker image is available locally
if ! docker image inspect "$IMAGE" &>/dev/null; then
    echo "Pulling $IMAGE (first run only, ~1.6GB)..."
    docker pull "$IMAGE"
fi

ACT_ARGS=(
    --pull=false
    -P "build=$IMAGE"
    -P "bench=$IMAGE"
    -P "ubuntu-latest=$IMAGE"
)

usage() {
    echo "Usage: $0 <workflow> [act flags...]"
    echo ""
    echo "Workflows:"
    echo "  pr        Run PR build (testDiff on linux)"
    echo "  main      Run main build (full test, all OSes)"
    echo "  scalafmt  Run scalafmt check"
    echo "  readme    Run readme check"
    echo "  lint      Run actionlint"
    echo "  list      List all workflows and jobs"
    echo "  dry-run   Dry-run PR build (show what would run)"
    echo ""
    echo "Examples:"
    echo "  $0 pr                          # run PR build"
    echo "  $0 pr -j build                 # run specific job"
    echo "  $0 scalafmt                    # check formatting"
    echo "  $0 dry-run                     # dry-run PR build"
    echo ""
    echo "First run requires pulling the Docker image:"
    echo "  docker pull catthehacker/ubuntu:act-latest"
}

if [ $# -eq 0 ]; then
    usage
    exit 0
fi

WORKFLOW="$1"
shift

case "$WORKFLOW" in
    pr)
        act pull_request \
            -W .github/workflows/build-pr.yml \
            "${ACT_ARGS[@]}" \
            "$@"
        ;;
    main)
        act push \
            -W .github/workflows/build-main.yml \
            "${ACT_ARGS[@]}" \
            "$@"
        ;;
    scalafmt)
        act push \
            -W .github/workflows/scalafmt.yml \
            "${ACT_ARGS[@]}" \
            "$@"
        ;;
    readme)
        act push \
            -W .github/workflows/readme.yml \
            "${ACT_ARGS[@]}" \
            "$@"
        ;;
    lint)
        act pull_request \
            -W .github/workflows/actionlint.yml \
            "${ACT_ARGS[@]}" \
            "$@"
        ;;
    list)
        act --list "${ACT_ARGS[@]}" "$@"
        ;;
    dry-run)
        act pull_request \
            -W .github/workflows/build-pr.yml \
            -n \
            "${ACT_ARGS[@]}" \
            "$@"
        ;;
    *)
        echo "Unknown workflow: $WORKFLOW"
        echo ""
        usage
        exit 1
        ;;
esac
