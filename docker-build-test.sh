#!/usr/bin/env bash
# Run incremental Gradle tasks inside Docker using graalvm-dev with a persistent Gradle cache volume
# and read-only host ~/.m2 mount for locally published libraries (e.g. com.scalarometer).
# Usage: ./docker-build-test.sh [gradle-tasks...]
# Examples:
#   ./docker-build-test.sh                 # incremental test
#   ./docker-build-test.sh clean test      # full clean test
#   TAG=ffmpeg ./docker-build-test.sh test # override image tag

set -euo pipefail
MEMORY=${MEMORY:-8gb}
GIT_PROJECT="${PWD##*/}"
IMAGE=${IMAGE:-graalvm-dev}
TAG=${TAG:-latest}
GRADLE_CACHE_VOLUME=${GRADLE_CACHE_VOLUME:-"${GIT_PROJECT}-gradle-cache"}
M2_DIR="${M2_DIR-${HOME}/.m2}"
if [ "$#" -eq 0 ]; then
  TASKS=(test)
else
  TASKS=("$@")
fi

M2_ARGS=()
if [ -n "${M2_DIR}" ] && [ -d "${M2_DIR}/repository" ]; then
  M2_ARGS=(-v "${M2_DIR}:/root/.m2:ro")
fi

docker run --name "${GIT_PROJECT}-build-$(date +%s)" \
  -e JAVA_TOOL_OPTIONS="--sun-misc-unsafe-memory-access=allow" \
  --network host \
  --rm \
  -v "$PWD:/git/${GIT_PROJECT}/" \
  -v "${GRADLE_CACHE_VOLUME}:/root/.gradle" \
  ${M2_ARGS[@]+"${M2_ARGS[@]}"} \
  -w "/git/${GIT_PROJECT}" \
  --memory="${MEMORY}" \
  "${IMAGE}:${TAG}" \
  gradle --no-daemon --no-watch-fs "${TASKS[@]}"
