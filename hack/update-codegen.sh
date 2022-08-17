#!/usr/bin/env bash

set -o errexit
set -o nounset
set -o pipefail
set -x

SCRIPT_ROOT=$(dirname "${BASH_SOURCE[0]}")/..

CODEGEN_PKG_PATH=$(go list -m -f '{{.Dir}}' "${CODEGEN_PKG}")

echo ${CODEGEN_PKG_PATH}

GOBIN="$(greadlink -f ${SCRIPT_ROOT})/.dev/bin" bash "${CODEGEN_PKG_PATH}"/generate-groups.sh \
  all \
  github.com/everpeace/kube-throttler/pkg/generated \
  github.com/everpeace/kube-throttler/pkg/apis \
  "schedule:v1alpha1" \
  --go-header-file "${SCRIPT_ROOT}"/hack/boilerplate/boilerplate.generatego.txt
