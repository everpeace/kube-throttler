module github.com/everpeace/kube-throttler

go 1.16

require (
	github.com/google/go-cmp v0.5.5
	github.com/onsi/ginkgo v1.16.4
	github.com/onsi/gomega v1.14.0
	github.com/pkg/errors v0.9.1
	github.com/prometheus/client_golang v1.7.1
	github.com/spf13/cobra v1.2.1
	k8s.io/api v0.20.5
	k8s.io/apimachinery v0.20.5
	k8s.io/apiserver v0.20.5
	k8s.io/client-go v0.20.5
	k8s.io/component-base v0.20.5
	k8s.io/klog/v2 v2.8.0
	k8s.io/kube-scheduler v0.20.5 // indirect
	k8s.io/kubernetes v1.20.5
	k8s.io/utils v0.0.0-20210629042839-4a2b36d8d73f
)

replace (
	// see https://github.com/kubernetes/kubernetes/blob/v1.20.5/go.mod#L473
	google.golang.org/grpc => google.golang.org/grpc v1.27.1
	k8s.io/api => k8s.io/api v0.20.5
	k8s.io/apiextensions-apiserver => k8s.io/apiextensions-apiserver v0.20.5
	k8s.io/apimachinery => k8s.io/apimachinery v0.20.5
	k8s.io/apiserver => k8s.io/apiserver v0.20.5
	k8s.io/cli-runtime => k8s.io/cli-runtime v0.20.5
	k8s.io/client-go => k8s.io/client-go v0.20.5
	k8s.io/cloud-provider => k8s.io/cloud-provider v0.20.5
	k8s.io/cluster-bootstrap => k8s.io/cluster-bootstrap v0.20.5
	k8s.io/code-generator => k8s.io/code-generator v0.20.5
	k8s.io/component-base => k8s.io/component-base v0.20.5
	k8s.io/component-helpers => k8s.io/component-helpers v0.20.5
	k8s.io/controller-manager => k8s.io/controller-manager v0.20.5
	k8s.io/cri-api => k8s.io/cri-api v0.20.5
	k8s.io/csi-translation-lib => k8s.io/csi-translation-lib v0.20.5
	k8s.io/kube-aggregator => k8s.io/kube-aggregator v0.20.5
	k8s.io/kube-controller-manager => k8s.io/kube-controller-manager v0.20.5
	k8s.io/kube-proxy => k8s.io/kube-proxy v0.20.5
	k8s.io/kube-scheduler => k8s.io/kube-scheduler v0.20.5
	k8s.io/kubectl => k8s.io/kubectl v0.20.5
	k8s.io/kubelet => k8s.io/kubelet v0.20.5
	k8s.io/kubernetes => k8s.io/kubernetes v1.20.5
	k8s.io/legacy-cloud-providers => k8s.io/legacy-cloud-providers v0.20.5
	k8s.io/metrics => k8s.io/metrics v0.20.5
	k8s.io/mount-utils => k8s.io/mount-utils v0.20.5
	k8s.io/sample-apiserver => k8s.io/sample-apiserver v0.20.5
)
