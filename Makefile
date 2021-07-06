# env
export GO111MODULE=on
export CGO_ENABLED=0

# project metadta
NAME         := kube-throttler
VERSION      ?= $(if $(RELEASE),$(shell git semv now),$(shell git semv patch -p))
REVISION     := $(shell git rev-parse --short HEAD)
IMAGE_PREFIX ?= kube-throttler/
IMAGE_NAME   := $(if $(RELEASE),release,dev)
IMAGE_TAG    ?= $(if $(RELEASE),$(VERSION),$(VERSION)-$(REVISION))
LDFLAGS      := -ldflags="-s -w -X \"github.com/everpeace/kube-throttler/cmd.Version=$(VERSION)\" -X \"github.com/everpeace/kube-throttler/cmd.Revision=$(REVISION)\" -extldflags \"-static\""
OUTDIR       ?= ./dist

.DEFAULT_GOAL := build

.PHONY: setup
setup:
	cd $(shell go env GOPATH) && \
	go get -u golang.org/x/tools/cmd/goimports && \
	curl -sSfL https://raw.githubusercontent.com/golangci/golangci-lint/master/install.sh | sh -s -- -b $(shell go env GOPATH)/bin v1.27.0 && \
	go get -u github.com/elastic/go-licenser && \
	go get -u github.com/linyows/git-semv/cmd/git-semv

.PHONY: fmt
fmt:
	$(shell go env GOPATH)/bin/goimports -w cmd/
	$(shell go env GOPATH)/bin/go-licenser --licensor "Shingo Omura"

.PHONY: lint
lint: fmt
	$(shell go env GOPATH)/bin/golangci-lint run --config .golangci.yml --deadline 30m

.PHONY: build
build: fmt lint
	go build -tags netgo -installsuffix netgo $(LDFLAGS) -o $(OUTDIR)/$(NAME) .

.PHONY: build-only
build-only: 
	go build -tags netgo -installsuffix netgo $(LDFLAGS) -o $(OUTDIR)/$(NAME) .

.PHONY: test
test: fmt lint
	go test  ./...

.PHONY: clean
clean:
	rm -rf "$(OUTDIR)"

.PHONY: build-image
build-image:
	docker build -t $(shell make -e docker-tag) --build-arg RELEASE=$(RELEASE) --build-arg VERSION=$(VERSION) --target runtime .
	docker tag $(shell make -e docker-tag) $(IMAGE_PREFIX)$(IMAGE_NAME):$(VERSION)  # without revision
	docker tag $(shell make -e docker-tag) $(IMAGE_PREFIX)$(IMAGE_NAME):latest      # latest

.PHONY: push-image
push-image:
	docker push $(shell make -e docker-tag)
	docker push $(IMAGE_PREFIX)$(IMAGE_NAME):$(VERSION) # without revision
	docker push $(IMAGE_PREFIX)$(IMAGE_NAME):latest     # latest

.PHONY: docker-tag
docker-tag:
	@echo $(IMAGE_PREFIX)$(IMAGE_NAME):$(IMAGE_TAG)

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
