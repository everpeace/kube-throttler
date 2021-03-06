#!/usr/bin/env bash
set -e

sbt ++${TRAVIS_SCALA_VERSION} test

if [ "${TRAVIS_PULL_REQUEST}" == "false" ]; then
    if grep "SNAPSHOT" version.sbt >/dev/null 2>&1; then
        echo "$DOCKER_PASSWORD" | docker login -u everpeace --password-stdin
        sbt ++${TRAVIS_SCALA_VERSION} docker:stage docker:publish
    fi
fi
