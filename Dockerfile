FROM golang:1.20 as builder
ARG RELEASE
ARG VERSION
WORKDIR /workspace
COPY go.mod go.mod
COPY go.sum go.sum
RUN go mod download

COPY . /workspace
RUN make build-only

FROM ubuntu:18.04 as runtime
RUN apt-get update && apt-get install -y ca-certificates && apt-get clean && rm -rf /var/lib/apt/lists/*
WORKDIR /
COPY --from=builder /workspace/dist/kube-throttler /usr/local/bin/kube-throttler
ENTRYPOINT ["/usr/local/bin/kube-throttler"]
CMD ["kube-scheduler"]
