# kube-throttler : throttling your pods in kubernetes cluster

`kube-throttler` enables you to throttle your pods.   It means that `kube-throttler` can prohibit to schedule any pods when it detects total amount of computational resource(in terms of `resources.requests` field) or the count of `Running` pods may exceeds a threshold .

`kube-throttler` provides you very flexible and fine-grained throttle control.  You can specify a set of pods which you want to throttle by [label selector](https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/) and its threshold by `Throttle`/`ClusterThrottle` CRD (see [deploy/0-crd.yaml](deploy/0-crd.yaml) for complete definition).

Throttle control is fully dynamic.  Once you update throttle setting, `kube-throttler` follow the setting and change its status in up-to-date.

## What differs from `Quota`?

`Quota` returns error when you tried to create pods if you requested resource which exceeds the quota.  However `Throttle` won't return any errors when creating pods but keep your pods stay `Pending` state by just throttling running pods.

And `Quota` is based on `Namespace` which is the unit of multi tenancy in Kubernetes.  `Throttle` provides a kind of virtual computational resource pools in more dynamic and more finer grained way.

## Installation

`kube-throttler` is implemented as a kubernetes scheduler plugin by [Scheduling Framework](https://kubernetes.io/docs/concepts/scheduling-eviction/scheduling-framework/).

There are two ways to use `kube-throttler`:

- Using pre-build binary
- Integrate `kube-throttler` with your scheduler plugins

### Pre-build binary

`kube-throttler` ships pre-build binary/container images which `kube-throttler` is integrated with kube-scheduler.

#### 1. deploy `kube-throttler` in your cluster

```shell
kubectl create -f deploy/
```

This creates:

- `kube-throttler` namespace, service accounts, RBAC entries
  - this will create a cluster role and cluster role binding.  please see [deploy/2-rbac.yaml](deploy/2-rbac.yaml) for detail.
- custom `kube-throttler` integrated `kube-scheduler` [deployment](deployment.yaml)
  - with sample [scheduler config](deploy/config.yaml)
    - scheduler name is `my-scheduler`
    - throttler name is `kube-throttler`

### Integrate kube-throttler with your kube-scheduler plugins

#### 1. Register `kube-throttler` in your scheduler

You need to register `kube-throttler` to your scheduler by calling `app.WithPlugin()` like this:

```go
...
import (
	"time"

	kubethrottler "github.com/everpeace/kube-throttler/pkg/scheduler_plugin"
	"k8s.io/component-base/logs"
	"k8s.io/kubernetes/cmd/kube-scheduler/app"
)

func main() {
	command := app.NewSchedulerCommand(
		...
		app.WithPlugin(kubethrottler.PluginName, kubethrottler.NewPlugin),
	)

	logs.InitLogs()
	defer logs.FlushLogs()

	if err := command.Execute(); err != nil {
		os.Exit(1)
	}
}
```

See these documents and repos for details of Scheduling Framework:

- [Scheduling Framework's Official Document](https://kubernetes.io/docs/concepts/scheduling-eviction/scheduling-framework/)
- [Scheduler Plugins - Repository for out-of-tree scheduler plugins based on the scheduler framework.](https://github.com/kubernetes-sigs/scheduler-plugins)

### 2. add roles to your scheduler service account

`kube-throttler` requires [`kube-throttler`] cluster roles defined in [deploy/rbac.yaml](deploy/rbac.yaml)

### 3. enable kube-throttler in your scheduler config

You need to enable `kube-throttler` in your scheduler config.  See [deploy/config.yaml](deploy/config.yaml)

## `Throttle` CRD

a `Throttle` custom resource defines three things:

- throttler name which is responsible for this `Throttle` custom resource.
- a set of pods to which the throttle affects by `selector`
  - please note that throttler only counts running pods which is responsible for configured target scheduler names.
- threshold of 
  - resource amount of `request`-ed computational resource of the throttle
  - count of resources (currently only `pod` is supported)
  - those can be overridden by `temporaryThresholdOverride`.  Please refer to [below section](#temporary-threshold-override).

And it also has `status` field. `status` field contains:

- `used` shows the current total usage of `reauest`-ed resource amount or counts of `Running` pods matching `selector`
- `calculatedThreshold` shows the calculated threshold value which takes `temporaryThresholdOverride` into account.  
- `throttled` shows the throttle is active for each resource requests or resource counts.

```yaml
# example/throttle.yaml
apiVersion: schedule.k8s.everpeace.github.com/v1alpha1
kind: Throttle
metadata:
  name: t1
spec:
  # throttler name which responsible for this Throttle custom resource
  throttlerName: kube-throttler
  # you can write any label selector freely
  # items under selecterTerms are evaluated OR-ed
  # each selecterTerm item are evaluated AND-ed 
  selector:
    selecterTerms:
    - podSelector:
        matchLabels:
          throttle: t1
  # you can set a threshold of the throttle
  threshold:
    # limiting total count of resources
    resourceCounts:
      # limiting count of running pods
      pod: 3 
    # limiting total amount of resource which running pods can `requests`
    resourceRequests: 
      cpu: 200m
status:
  # 'throttled' shows throttle status defined in spec.threshold.
  # when you tried to create a pod, all your 'request'-ed resource's throttle 
  # and count of resources should not be throttled
  throttled:
    resourceCounts:
      pod: false
    resourceRequests:
      cpu: true
  # 'used' shows total 'request'-ed resource amount and count of 'Running' pods 
  # matching spec.selector
  used:
    resourceCounts:
      pod: 1
    resourceRequests:
      cpu: 300m
```

### Temporary Threshold Overrides

User sometimes increase/decrease threshold value.  You can edit `spec.threshold` directly.  However, what if the increase/decrease is expected in limited term??  Temporary threshold overrides can solve it.

Temporary threshold overrides provides declarative threshold override.  It means, override automatically activated when the term started and expired automatically when the term finished.  It would greatly reduces operational tasks.

`spec` can have `temporaryThresholdOverrides` like this:

```yaml
apiVersion: schedule.k8s.everpeace.github.com/v1alpha1
kind: Throttle
metadata:
  name: t1
spec:
  threshold:
    resourceCounts:
      pod: 3 
    resourceRequests: 
      cpu: 200m
      memory: "1Gi"
      nvidia.com/gpu: "2"
  temporaryThresholdOverrides:
  # begin/end should be a datetime string in RFC3339
  # each entry is active when t in [begin, end]
  # if multiple entries are active all active threshold override 
  # will be merged (first override lives for each resource count/request).
  - begin: 2019-02-01T00:00:00+09:00
    end: 2019-03-01T00:00:00+09:00
    threshold:
      resourceRequests:
        cpu: "5"
  - begin: 2019-02-15T00:00:00+09:00
    end: 2019-03-01T00:00:00+09:00
    threshold:
      resourceRequests:
        cpu: "1"
        memory: "8Gi"
```

`temporaryTresholds` can define multiple override entries.  Each entry is active when current time is in `[begin, end]` (inclusive on both end).  If multiple entries are active, all active overrides will be merged. First override lives for each resource count/request.  For above example, if current time was '2019-02-16T00:00:00+09:00', both overrides are active and merged threshold will be:

```yaml
resourceCounts:    # this is not overridden 
  pod: 3
resourceRequests:
  cpu: "5"         # from temporaryThresholdOverrides[0]
  memory: "8Gi"    # from temporaryThresholdOverrides[1]
```

These calculated threshold value are recoreded in `staus.calculatedThrottle` field.  __The field matters when deciding throttle is active or not.__

## How `kube-throttler` works

I describe a simple scenario here.  _Note that this scenario holds with `ClusterThrottle`.  The only difference between them is `ClusterThrottles` can targets pods in multiple namespaces but `Throttle` can targets pods only in the same namespace with it._

- define a throttle `t1` which targets `throttle=t1` label and threshold `cpu=200m` and `memory=1Gi`.
- create `pod1` with the same label and `requests` `cpu=200m`
- then, `t1` status will transition to `throttled: cpu: true` because total amount of `cpu` of running pods reaches its threshold. 
- create `pod2` with the same label and `requests` `cpu=300m` and see the pod stays `Pending` state because `cpu` was throttled.
- create `pod1m` with same label and `requests` `memory=512Mi`.  ane see the pod will be scheduled because `t1` is throttled only on `cpu` and `memory` is not throttled.
- update `t1` threshold with `cpu=700m`, then throttle will open and see `pod2` will be scheduled.
- `t1`'s `cpu` capacity remains `200m` (threshold is `cpu=700m` and used `cpu=500m`) now.
- then, create `pod3` with same label and `requests` `cpu=300m`. kube-throttler detects no enough space left for `cpu` resource in `t1`.  So, `pod3` stays `Pending. 

Lets' create `Thrttle` first. 

```shell
kubectl create -f example/throttle.yaml 
```

Just after a while, you can see the status of the throttle change:

```shell
$ kubectl get throttle t1 -o yaml
...
spec:
  throttlerName: kube-throttler
  selector:
    selecterTerms:
    - podSelector:
        matchLabels:
          throttle: t1
  threshold:
    resourceCounts:
      pod: 5
    resourceRequests:
      cpu: 200m
      memory: 1Gi
status:
  throttled:
    resourceCounts:
      pod: false
    resourceRequests:
      cpu: false
      memory: false
  used: 
    resourceRequests: {}
```

Then, create a pods with label `throttle=t1` and `requests` `cpu=300m`.

```shell
kubectl create -f example/pod1.yaml
```

after a while, you can see throttle `t1` will be activated on `cpu`.

```shell
$ kubectl get throttle t1 -o yaml
...
status:
  throttled:
    resourceCounts:
      pod: false
    resourceRequests:
      cpu: true
      memory: false
  used:
    resourceCounts:
      pod: 1
    resourceRequests:
      cpu: "0.200"
```

Next, create another pod then you will see the pod will be throttled and keep stay `Pending` state by `kube-throttler`.

```shell
$ kubectl create -f example/pod2.yaml
$ kubectl describe pod pod2
...
Events:
  Type     Reason            Age               From               Message
  ----     ------            ----              ----               -------
  Warning  FailedScheduling  14s (x9 over 1m)  my-scheduler       pod is unschedulable due to throttles[active]=(default,t1)
```

In this situation, you can run `pod1m` requesting `memory=512Mi` because `t1`'s `memory` throttle is not throttled.

```shell
$ kubectl create -f example/pod1m.yaml
$ kubectl get po pod1m
NAME      READY     STATUS    RESTARTS   AGE
pod1m     1/1       Running   0          24s
$ kubectl get throttle t1 -o yaml
...
status:
  throttled:
    resourceCounts:
      pod: false
    resourceRequests:
      cpu: true
      memory: false
  used:
    resourceCounts:
      pod: 2
    resourceRequests:
      cpu: "0.200"
      memory: "536870912"
```

Then, update `t1` threshold with `cpu=700m`

```shell
$ kubectl edit throttle t1
# Please edit threshold section 'cpu: 200m' ==> 'cpu: 700m'

$ kubectl describe pod pod2
Events:
  Type     Reason            Age               From               Message
  ----     ------            ----              ----               -------
  Warning  FailedScheduling  14s (x9 over 1m)  my-scheduler       pod is unschedulable due to throttles[active]=(default,t1)
  Normal   Scheduled         7s                my-scheduler       Successfully assigned default/pod-r8lxq to minikube
  Normal   Pulling           6s                kubelet, minikube  pulling image "busybox"
  Normal   Pulled            4s                kubelet, minikube  Successfully pulled image "busybox"
  Normal   Created           3s                kubelet, minikube  Created container
  Normal   Started           3s                kubelet, minikube  Started container
```

You will also see `t1` status now stays open.

```shell
$ kubectl get throttle t1 -o yaml
...
spec:
  selector:
    selecterTerms:
    - podSelector:
        matchLabels:
          throttle: t1
  threshold:
    resourceCounts:
      pod: 5
    resourceRequests:
      cpu: 700m
      memory: 1Gi
status:
  throttled:
    resourceCounts:
      pod: false
    resourceRequests:
      cpu: false
      memory: false
  used:
    resourceCounts:
      pod: 3
    resourceRequests:
      cpu: "0.500"
      memory: "536870912"
```

Now, `t1` remains `cpu:200m` capacity.  Then, create `pod3` requesting `cpu:300m`.  `pod3` stays `Pending` state because `t1` does not have enough capacity on `cpu` resources.  

```shell
$ kubectl create -f example/pod3.yaml
$ kubectl get po pod3
NAME   READY   STATUS    RESTARTS   AGE
pod3   0/1     Pending   0          5s
$ kubectl describe pod pod3
...
Events:
  Type     Reason            Age               From          Message
  ----     ------            ----              ----          -------
  Warning  FailedScheduling  9s (x3 over 13s)  my-scheduler  0/1 nodes are available: 1 pod (default,pod3) is unschedulable due to , throttles[insufficient]=(default,t1)
```

## Monitoring with Prometheus

`kube-throttler` exports prometheus metrics. Metrics are served on kube-scheduler's metrics endpoint. `kube-throttler` exports metrics below:

| metrics name | definition | example |
--------------|------------|---------
| throttle_status_throttled_resourceRequests | resourceRequests of the throttle is throttled or not on specific resource (`1=throttled`, `0=not throttled`). |`throttle_status_throttled_resourceRequests{name="t1", namespace="default",uuid="...",resource="cpu"} 1.0`
| throttle_status_throttled_resourceCounts | resourceCounts of the throttle is throttled or not on specific resource (`1=throttled`, `0=not throttled`). |`throttle_status_throttled_resourceRequests{name="t1", namespace="default",uuid="...",resource="pod"} 1.0`
| throttle_status_used_resourceRequests | used amount of resource requests of the throttle |`throttle_status_used_resourceRequests{name="t1", namespace="default",uuid="...",resource="cpu"} 200`
| throttle_status_used_resourceCounts | used resource counts of the throttle |`throttle_status_used_resourceCounts{name="t1", namespace="default",uuid="...",resource="pod"} 2`     
| throttle_status_calculated_threshold_resourceRequests | calculated threshold on specific resourceRequests of the throttle |`throttle_status_calculated_threshold_resourceRequests{name="t1", namespace="default",uuid="...",resource="pod"} 2`
| throttle_status_calculated_threshold_resourceCounts | calculated threshold on specific resourceCounts of the throttle |`throttle_status_calculated_threshold_resourceCounts{name="t1", namespace="default",uuid="...",resource="cpu"} 200`
| throttle_spec_threshold_resourceRequests | threshold on specific resourceRequests of the throttle |`throttle_spec_threshold_resourceRequests{name="t1", namespace="default",uuid="...",resource="pod"} 2`
| throttle_spec_threshold_resourceCounts | threshold on specific resourceCounts of the throttle |`throttle_spec_threshold_resourceCounts{name="t1", namespace="default",uuid="...",resource="cpu"} 200`
| clusterthrottle_status_throttled_resourceRequests | resourceRequests of the clusterthrottle is throttled or not on specific resource (`1=throttled`, `0=not throttled`). |`clusterthrottle_status_throttled_resourceRequests{name="clt1",uuid="...",resource="cpu"} 1.0`
| clusterthrottle_status_throttled_resourceCounts | resourceCounts of the clusterthrottle is throttled or not on specific resource (`1=throttled`, `0=not throttled`). |`clusterthrottle_status_throttled_resourceRequests{name="clt1",uuid="...",resource="pod"} 1.0`
| clusterthrottle_status_used_resourceRequests | used amount of resource requests of the clusterthrottle |`clusterthrottle_status_used_resourceRequests{name="t1",uuid="...",resource="cpu"} 200`
| clusterthrottle_status_used_resourceCounts | used resource counts of the clusterthrottle |`clusterthrottle_status_used_resourceCounts{name="clt1",uuid="...",resource="pod"} 2`
| clusterthrottle_status_calculated_threshold_resourceRequests | calculated threshold on specific resourceRequests of the clusterthrottle |`clusterthrottle_status_calculated_threshold_resourceRequests{name="t1",uuid="...",resource="pod"} 2`
| clusterthrottle_status_calculated_threshold_resourceCounts | calculated threshold on specific resourceCounts of the clusterthrottle |`clusterthrottle_status_calculated_threshold_resourceCounts{name="t1",uuid="...",resource="cpu"} 200`
| clusterthrottle_spec_threshold_resourceRequests | threshold on specific resourceRequests of the clusterthrottle |`clusterthrottle_spec_threshold_resourceRequests{name="t1",uuid="...",resource="pod"} 2`
| clusterthrottle_spec_threshold_resourceCounts | threshold on specific resourceCounts of the clusterthrottle |`clusterthrottle_spec_threshold_resourceCounts{name="t1",uuid="...",resource="cpu"} 200`

## License

Apache License 2.0

## Change Logs (`< 1.0.0`)

Since `1.0.0`, change logs have been published in Github releases.

## `0.7.4`

- Fixed
  - fail fast the liveness probe when kubernetes api watch stopped(#23)
  
## `0.7.3`

- Fixed
  - Watching Kubernetes events stopped when some watch source faced error (#22)

## `0.7.2`

- Changed
  - upgraded [`skuber`](https://github.com/doriordan/skuber) version to `v2.2.0`
  - periodic throttle reconciliation which limits those which really need to

## `0.7.1`

- Fixed
  - reduced memory usage for large cluster.  `kube-throttler` does not cache completed(`status.phase=Succeeded|Failed`) pods anymore.  

## `0.7.0`

- Added
  - support `Preempt` scheduler extender at `/preempt_if_not_throttled` endpoint in order to prevent from undesired preemptions when high priority pods are throttled.

## `0.6.0`

- Changed
  - `status.used` counts on not only `Running` pod but all _scheduled_ Pod
    - _scheduled_ means pod assigned to some node but not finished.  `pod.status.phase != (Succeeded or Failed) && spec.nodeName is nonEmpty`
  - skip unnecessary calculation when pod changed.  It reduces controller's load when pod churn rate is high.
  - temporary threshold override reconciliation is now performed asynchronously

## `0.5.3`

all changes are for performance issue.

- Changed
  - now http server's request handling can be performed in isolated thread pool.
  - checking a pod is throttled are performed in different actor (`ThrottleRequestHandler`)
  - healthcheck is now performed in different actor `WatchActor`.
- Fixed
  - can't collect dispatcher metrics. 

## `0.5.2`

- Fixed
  - too frequent updates on throttles/clusterthrottles calculated threshold
- Added
  - introduced `status-force-update-interval (default: 15 m)` parameter to update calculated threshold forcefully even if its threshold values are unchanged.

## `0.5.1`

- Fixed
  - "too old resource version" on init reconciliation for clusters with large number of throttles/clusterthrottles
- Changed
  - log level for all the metrics changes is now debug.

## `0.5.0`

- Added
  - `temporaryThresholdOverrides` introduced.  User now can define declarative threshold override with finite term by using this.  `kube-throttler` activates/deactivates those threshold overrides.
  - `status.calculatedThreshold` introduced.  This fields shows the latest calculated threshold. The field matters when deciding throttle is active or not. `[cluster]throttle_status_calculated_threshold` are also introduced.

## `0.4.0`

- Changed
  - __BREAKING CHANGE__:  change `spec.selector` object schema to support OR-ed multiple label selectors and `namespaceSelector` in clusterthrottles. (#6)

### Migration Notes from `0.3.x` or before

- stop kube-throttlers (recommend to make `replicas` to 0)
- dump your all throttle/clusterthrottles `kubectl get clusterthrottles,throttles --all-namespaces`
- replace `selector.matchLabels` with `selector.selectorTerms[0].podSelecter.matchLabels` in your crs
    ```yaml
    # before
    spec:
      selector:
        matchLabels: { some object }
        matchExpressions: [ some arrays ]
    
    # after
    spec:
      selector:
        selectorTerms:
        - podSelector:
            matchLabels: { some object }
            matchExpressions: [ some arrays ]
    ```
- delete all throttle/clusterthrottles `kubectl delete clusterthrottles,throttles --all-namespaces --all`
- update crds and rbacs `kubectl apply -f deploy/0-crd.yaml; kubectl apply -f deploy/2-rbac.yaml`
- start kube-throttlers (recommend to make `replicas` back to the original value)
- apply updated throttles/clusterthrottoles crs.
 
## `0.3.2`

- Changed
  - large refactoring #4 (moving throttle logic to model package from controller package)
  - skip un-marshalling `matchFields` field in `NodeSelectorTerm`.
    - the attribute has been supported since kubernetes `v1.11`.

## `0.3.1`

- Changed
  - sanitize invalid characters in metrics labels
  - remove `metadata.annotations` from metrics labels
 
## `0.3.0`

- Added
  - `resourceCounts.pod` in `Throttle`/`ClusterThrottle` so that user can throttle count of `running` pod.
- Changed
  - previous compute resource threshold should be defined in `resourceRequests.{cpu|memory}`.

## `0.2.0`

- introduce `ClusterThrottle` which can target pods in multiple namespaces.
- make `Throttle`/`ClusterThrottle` not burstable. This means if some throttle remains `cpu:200m` and pod requesting `cpu:300` is trie to schedule, kube-throttler does not allow the pod to be scheduled.  At that case, message of `throttles[insufficient]=<throttle name>` will be returned to scheduler.

## `0.1.3`

- `watch-buff-size` can be configurable for large pods
- properly handle initial sync error

## `0.1.2`

- multi-throttler, multi-scheduler deployment support
  - `throttlerName` is introduced in `Throttle` CRD
  - `throttler-name` and `target-scheduler-names` are introduced in throttler configuration

## `0.1.1`

- fixed returning filter error when normal throttled situation.

## `0.1.0`

first public release.
