#!/usr/bin/env bash
set -e

grep -v "SNAPSHOT" version.sbt >/dev/null 2>&1
echo "$DOCKER_PASSWORD" | docker login -u everpeace --password-stdin
sbt ++${TRAVIS_SCALA_VERSION} docker:stage docker:publish
