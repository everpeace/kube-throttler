# env
export GO111MODULE=on
export CGO_ENABLED=0

# project metadta
NAME         := kube-throttler
VERSION      ?= $(if $(RELEASE),$(shell $(GIT_SEMV) now),$(shell $(GIT_SEMV) patch -p))
REVISION     := $(shell git rev-parse --short HEAD)
IMAGE_PREFIX ?= 
IMAGE_TAG    ?= $(if $(RELEASE),$(VERSION),$(VERSION)-$(REVISION))
LDFLAGS      := -ldflags="-s -w -X \"github.com/everpeace/kube-throttler/cmd.Version=$(VERSION)\" -X \"github.com/everpeace/kube-throttler/cmd.Revision=$(REVISION)\" -extldflags \"-static\""
OUTDIR       ?= ./dist

.DEFAULT_GOAL := build

.PHONY: fmt
fmt:
	$(GO_IMPORTS) -w cmd/ pkg/
	$(GO_LICENSER) --licensor "Shingo Omura"

.PHONY: lint
lint: fmt
	$(GOLANGCI_LINT) run --config .golangci.yml --deadline 30m

.PHONY: build
build: fmt lint
	go build -tags netgo -installsuffix netgo $(LDFLAGS) -o $(OUTDIR)/$(NAME) .

.PHONY: install
install:
	kubectl apply -f ./deploy/crd.yaml

.PHONY: generate
generate: codegen crd

.PHONY: codegen
codegen:
	CODEGEN_PKG=$(CODEGEN_PKG) ./hack/update-codegen.sh
	$(GO_LICENSER) --licensor "Shingo Omura"

.PHONY: crd
crd:
	$(CONTROLLER_GEN) crd paths=./pkg/apis/... output:stdout > ./deploy/crd.yaml

.PHONY: build-only
build-only: 
	go build -tags netgo -installsuffix netgo $(LDFLAGS) -o $(OUTDIR)/$(NAME) .

.PHONY: test
test: fmt lint
	go test  $$(go list ./... | grep -v "test/integration")

.PHONY: clean
clean:
	rm -rf "$(OUTDIR)"

.PHONY: build-image
build-image:
	docker build -t $(shell make -e docker-tag) --build-arg RELEASE=$(RELEASE) --build-arg VERSION=$(VERSION) --target runtime .
	docker tag $(shell make -e docker-tag) $(IMAGE_PREFIX)$(NAME):$(VERSION)  # without revision

.PHONY: push-image
push-image:
	docker push $(shell make -e docker-tag)
	# without revision
	docker push $(IMAGE_PREFIX)$(NAME):$(VERSION)
	# latest (update only in release)
	$(if $(RELEASE), docker tag $(shell make -e docker-tag) $(IMAGE_PREFIX)$(NAME):latest)
	$(if $(RELEASE), docker push $(IMAGE_PREFIX)$(NAME):latest)  

.PHONY: docker-tag
docker-tag:
	@echo $(IMAGE_PREFIX)$(NAME):$(IMAGE_TAG)

#
# Release
#
guard-%:
	@ if [ "${${*}}" = "" ]; then \
    echo "Environment variable $* is not set"; \
		exit 1; \
	fi
.PHONY: release
release: guard-RELEASE guard-RELEASE_TAG
	git diff --quiet HEAD || (echo "your current branch is dirty" && exit 1)
	git tag $(RELEASE_TAG) $(REVISION)
	git push origin $(RELEASE_TAG)


#
# dev setup
#
.PHONY: setup
DEV_TOOL_PREFIX = $(shell pwd)/.dev
GIT_SEMV = $(DEV_TOOL_PREFIX)/bin/git-semv
GOLANGCI_LINT = $(DEV_TOOL_PREFIX)/bin/golangci-lint
GO_LICENSER = $(DEV_TOOL_PREFIX)/bin/go-licenser 
GO_IMPORTS = $(DEV_TOOL_PREFIX)/bin/goimports
CONTROLLER_GEN = $(DEV_TOOL_PREFIX)/bin/controller-gen
CODEGEN_PKG=$(CODEGEN_PKG_NAME)@$(CODEGEN_PKG_VERSION)
CODEGEN_PKG_NAME=k8s.io/code-generator
CODEGEN_PKG_VERSION=v0.23.4
KIND = $(DEV_TOOL_PREFIX)/bin/kind
KIND_KUBECNOFIG = $(DEV_TOOL_PREFIX)/.kubeconfig
setup:
	GOBIN=$(DEV_TOOL_PREFIX)/bin go install golang.org/x/tools/cmd/goimports@latest
	GOBIN=$(DEV_TOOL_PREFIX)/bin go install github.com/elastic/go-licenser@latest
	GOBIN=$(DEV_TOOL_PREFIX)/bin go install github.com/linyows/git-semv/cmd/git-semv@latest
	GOBIN=$(DEV_TOOL_PREFIX)/bin go install sigs.k8s.io/controller-tools/cmd/controller-gen@v0.6.1
	go mod download $(CODEGEN_PKG)
	GOBIN=$(DEV_TOOL_PREFIX)/bin go install $(CODEGEN_PKG_NAME)/cmd/defaulter-gen@$(CODEGEN_PKG_VERSION)
	GOBIN=$(DEV_TOOL_PREFIX)/bin go install $(CODEGEN_PKG_NAME)/cmd/client-gen@$(CODEGEN_PKG_VERSION)
	GOBIN=$(DEV_TOOL_PREFIX)/bin go install $(CODEGEN_PKG_NAME)/cmd/lister-gen@$(CODEGEN_PKG_VERSION)
	GOBIN=$(DEV_TOOL_PREFIX)/bin go install $(CODEGEN_PKG_NAME)/cmd/informer-gen@$(CODEGEN_PKG_VERSION)
	GOBIN=$(DEV_TOOL_PREFIX)/bin go install $(CODEGEN_PKG_NAME)/cmd/deepcopy-gen@$(CODEGEN_PKG_VERSION)
	GOBIN=$(DEV_TOOL_PREFIX)/bin go install sigs.k8s.io/kind@latest
	GOBIN=$(DEV_TOOL_PREFIX)/bin go install github.com/golangci/golangci-lint/cmd/golangci-lint@v1.46.2

#
# local development
# TIPS: You can change loglevel dynamicaly:
#   $ curl curl -XPUT --data 'N' localhost:10251/debug/flags/v
#
KUBECONFIG ?= $(HOME)/.kube/config
THROTTLER_NAME ?= kube-throttler
SCHEDULER_NAME ?= my-scheduler
.PHONY: dev-scheduler-conf
dev-scheduler-conf:
	mkdir -p .dev
	KUBECONFIG=$(KUBECONFIG) \
	THROTTLER_NAME=$(THROTTLER_NAME) \
	SCHEDULER_NAME=$(SCHEDULER_NAME) \
	envsubst < ./hack/dev/scheduler-config.yaml.template > ./hack/dev/scheduler-config.yaml

.PHONY: dev-run
dev-run: dev-scheduler-conf
	go run main.go kube-scheduler \
		--config=./hack/dev/scheduler-config.yaml \
		-v=3

.PHONY: dev-run-debug
dev-run-debug: dev-scheduler-conf
	dlv debug --headless --listen=0.0.0.0:2345 --api-version=2 --log main.go -- kube-scheduler \
		--config=./hack/dev/scheduler-config.yaml \
		--kubeconfig=$(HOME)/.kube/config \
		--v=3

#
# E2E test
#
export E2E_GOMEGA_DEFAULT_EVENTUALLY_TIMEOUT=180s
export E2E_GOMEGA_DEFAULT_CONSISTENTLY_DURATION=2s
E2E_PAUSE_IMAGE=k8s.gcr.io/pause:3.2
E2E_KIND_KUBECNOFIG = $(DEV_TOOL_PREFIX)/.kubeconfig
E2E_KIND_CONF=./hack/e2e/kind.conf
E2E_NODE_IMAGE ?= kindest/node:v1.23.4
e2e-setup:
	$(KIND) get clusters | grep kube-throttler-e2e 2>&1 >/dev/null \
	  || $(KIND) create cluster --name=kube-throttler-e2e \
	       --kubeconfig=$(E2E_KIND_KUBECNOFIG) \
		   --config=$(E2E_KIND_CONF) \
		   --image=$(E2E_NODE_IMAGE)
	kubectl --kubeconfig=$(E2E_KIND_KUBECNOFIG) apply -f ./deploy/crd.yaml
	docker pull $(E2E_PAUSE_IMAGE)
	$(KIND) load docker-image $(E2E_PAUSE_IMAGE) --name=kube-throttler-e2e
	kubectl --kubeconfig=$(E2E_KIND_KUBECNOFIG) wait --timeout=120s \
		--for=condition=Ready -n kube-system \
		node/kube-throttler-e2e-control-plane \
		pod/kube-apiserver-kube-throttler-e2e-control-plane

e2e-teardown:
	$(KIND) get clusters | grep kube-throttler-e2e 2>&1 >/dev/null \
	  && $(KIND) delete cluster --name=kube-throttler-e2e

e2e: fmt lint e2e-setup
	GOMEGA_DEFAULT_EVENTUALLY_TIMEOUT=$(E2E_GOMEGA_DEFAULT_EVENTUALLY_TIMEOUT) \
	GOMEGA_DEFAULT_CONSISTENTLY_DURATION=$(E2E_GOMEGA_DEFAULT_CONSISTENTLY_DURATION) \
	go test ./test/integration --kubeconfig=$(E2E_KIND_KUBECNOFIG) --pause-image=$(E2E_PAUSE_IMAGE)

e2e-debug: fmt lint e2e-setup
	GOMEGA_DEFAULT_EVENTUALLY_TIMEOUT=$(E2E_GOMEGA_DEFAULT_EVENTUALLY_TIMEOUT) \
	GOMEGA_DEFAULT_CONSISTENTLY_DURATION=$(E2E_GOMEGA_DEFAULT_CONSISTENTLY_DURATION) \
	dlv test --headless --listen=0.0.0.0:2345 --api-version=2 --log ./test/integration -- --kubeconfig=$(E2E_KIND_KUBECNOFIG) --pause-image=$(E2E_PAUSE_IMAGE)
