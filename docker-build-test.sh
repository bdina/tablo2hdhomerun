#!/usr/bin/env bash
# Run Gradle clean and test inside Docker using graalvm-dev:ffmpeg (matches ~/bin/docker-build pattern).
# Usage: ./docker-build-test.sh   or   TAG=ffmpeg ./docker-build-test.sh

set -e
MEMORY=${MEMORY:='8gb'}
GIT_PROJECT="${PWD##*/}"
IMAGE=${IMAGE:='graalvm-dev'}
TAG=${TAG:='ffmpeg'}

docker run --name "${GIT_PROJECT}-build-$(date +%s)" \
  -e JAVA_TOOL_OPTIONS="--sun-misc-unsafe-memory-access=allow" \
  --network host \
  --rm \
  -v "$PWD:/git/${GIT_PROJECT}/" \
  -w "/git/${GIT_PROJECT}" \
  --memory="${MEMORY}" \
  "${IMAGE}:${TAG}" \
  gradle --no-daemon clean test
