#!/usr/bin/env bash

set -o errexit
set -o nounset
set -o pipefail

SCRIPT_ROOT=$(dirname "${BASH_SOURCE[0]}")/..
CODEGEN_PKG=${CODEGEN_PKG:-$(ls -d -1 $(go env GOPATH)/pkg/mod/k8s.io/code-generator* 2>/dev/null || echo ../code-generator)}

echo ${CODEGEN_PKG}

bash "${CODEGEN_PKG}"/generate-groups.sh \
  all \
  github.com/everpeace/kube-throttler/pkg/generated \
  github.com/everpeace/kube-throttler/pkg/apis \
  "schedule:v1alpha1" \
  --go-header-file "${SCRIPT_ROOT}"/hack/boilerplate/boilerplate.generatego.txt
