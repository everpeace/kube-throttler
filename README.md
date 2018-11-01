# kube-throttler : throttling your pods in kubernetes cluster.
[![Build Status](https://travis-ci.org/everpeace/kube-throttler.svg?branch=master)](https://travis-ci.org/everpeace/kube-throttler) 
[![Docker Pulls](https://img.shields.io/docker/pulls/everpeace/kube-throttler.svg)](https://hub.docker.com/r/everpeace/kube-throttler/)

`kube-throttler` enables you to throttle your pods.   It means that `kube-throttler` can prohibit to schedule any pods when it detects total amount of computational resource of `Running` pods exceeds a threshold (in terms of `resources.requests` field). 

`kube-throttler` provides you very flexible and fine-grained throttle control.  You can specify a set of pods which you want to throttle by [label selector](https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/) and its threshold by `Throttle` CRD (see [deploy/0-crd.yaml](deploy/0-crd.yaml) for complete definition).

Throttle control is fully dynamic.  Once you update throttle setting, `kube-throttler` follow the setting and change its status in up-to-date. 


### What differs from `Quota`?  
`Quota` returns error when you tried to create pods if you requested resource which exceeds the quota.  However `Throttle` won't return any errors when creating pods but keep your pods stay `Pending` state by just throttling running pods.  

And `Quota` is based on `Namespace` which is the unit of multi tenancy in Kubernetes.  `Throttle` provides a kind of virtual computational resource pools in more dynamic and more finer grained way. 
  
## Installation

`kube-throttler` works as [kubernetes scheduler extender](https://github.com/kubernetes/community/blob/master/contributors/design-proposals/scheduling/scheduler_extender.md).

So, installation will be two steps

1. deploy `kube-throttler` in your cluster
2. configure your `kube-scheduler` to integrate `kube-throttler`

### 1. deploy `kube-throttler` in your cluster

```shell
kubectl create -f deploy/
``` 

This creates:
- `kube-throttler` namespace, service accounts, RBAC entries
  - this will create a cluster role and cluster role binding.  please see [deploy/2-rbac.yaml](deploy/2-rbac.yaml) for detail.
- `kube-throttller` deployment and its service so that kubernetes scheduler connect to it.
  - its throttler name is `kube-throttler`
  - its target scheduler is `my-scheduler`  (this throttler only counts running pods which is responsible for `my-scheduler`)
  - if you want to change this, please see [`application.conf`  in `kube-throttler-application-ini` configmap](deploy/3-deployment.yaml) 


### 2. configure your `kube-scheduler`

`kube-scheduler` supports policy based configuration.  You will need to set `kube-throttler` as an extender like below:

```json
{
  "kind" : "Policy",
  "apiVersion" : "v1",
 ...
  "extenders" : [
    {
        "urlPrefix": "http://extender.kube-throttler/",
        "filterVerb": "check_throttle",
        "prioritizeVerb": "",
        "preemptVerb": "",
        "bindVerb": "",
        "weight": 1,
        "enableHttps": false,
        "nodeCacheCapable": false
    }
  ]
}
```

please see [example/my-scheudler.yaml](example/my-scheduler.yaml) for complete example.

## `Throttle` CRD
a `Throttle` custom resource defines three things:

- throttler name which is responsible for this `Throttle` custom resource.
- a set of pods to which the throttle affects by `selector`
  - please note that throttler only counts running pods which is responsible for configured target scheduler names.
- threshold of amount of `request`-ed computational resource of the throttle

And it also has `status` field:
 
- `status` filed shows throttle status for each resources defined in threshold setting and current total usage of `reauest`-ed resource of `Running` pods matching `selector`

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
  selector:
    matchLabels:
      throttle: t1
  # you can set a threshold of the throttle
  # limiting total amount of resource whichRunning pods can `requests` 
  threshold:
    cpu: 200m
status:
  # 'throttled' shows throttle status for each resource defined in spec.threshold.
  # when you tried to create a pod, all your 'request'-ed resource's throttle 
  # should not throttled
  throttled:
    cpu: true
  # 'used' shows total 'request'-ed resource amount of 'Running' pods 
  # matching spec.selector
  used:
    cpu: 300m
    memory: 10Gi
```

## How `kube-throttler` works
I describe a simple scenario here.

- define a throttle `t1` which targets `throttle=t1` label and threshold `cpu=200m` and `memory=1Gi`.
- create `pod1` with the same label and `requests` `cpu=300m`
- then, `t1` status will transition to `throttled: cpu: true` because total amount of `cpu` of running pods exceeds its threshold. 
- create `pod2` with the same label and `requests` `cpu=300m` and see the pod stays `Pending` state because `cpu` was throttled.
- create `pod1m` with same label and `requests` `memory=512Mi`.  ane see the pod will be scheduled because `t1` is throttled only on `cpu` and `memory` is not throttled.
- update `t1` threshold with `cpu=700m`, then throttle will open and see `pod2` will be scheduled.

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
    matchLabels:
      throttle: t1
  threshold:
    cpu: 200m
    memory: 1Gi
status:
  throttled:
    cpu: false
  used: {}
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
    cpu: true
    memory: false
  used:
    cpu: "0.300"
```

Next, create another pod then you will see the pod will be throttled and keep stay `Pending` state by `kube-throttler`.

```shell
$ kubectl create -f example/pod2.yaml
$ kubectl describe pod pod2
...
Events:
  Type     Reason            Age               From               Message
  ----     ------            ----              ----               -------
  Warning  FailedScheduling  14s (x9 over 1m)  my-scheduler       pod is unschedulable due to throttles=t1
```

In this situation, you can run `pod1m` requesting `memory=512Mi` because `t1`'s `memory` throttle is not throttled.

```shell
$ kubectl create -f example/pod1m.yaml
$ k get po pod1m
NAME      READY     STATUS    RESTARTS   AGE
pod1m     1/1       Running   0          24s
$ k get throttle t1 -o yaml
...
status:
  throttled:
    cpu: true
    memory: false
  used:
    cpu: "0.300"
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
  Warning  FailedScheduling  14s (x9 over 1m)  my-scheduler       pod is unschedulable due to throttles=t1
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
    matchLabels:
      throttle: t1
  threshold:
    cpu: 700m
    memory: 1Gi
status:
  throttled: 
    cpu: false
    memory: false
  used:
    cpu: "0.600"
    memory: "536870912"
``` 


## Monitoring with Prometheus
`kube-throttler` exports prometheus metrics powered by [Kamon](https://kamon.io/). metrics are served on `http://kube-throttler.kube-throttler.svc:9095/metrics`.

`kube-throttler` exports metrics below:

| metrics name | definition | example |
--------------|------------|---------   
| throttle_status_throttled | the throttle is throttled or not on specific resource (`1=throttled`, `0=not throttled`). |`throttle_status_throttled{name="t1", namespace="default",uuid="...",resource="cpu"} 1.0`     
| throttle_status_used | used amount of resource of the throttle |`throttle_status_used{name="t1", namespace="default",uuid="...",resource="cpu"} 200`     
| throttle_spec_threshold | threshold on specific resource of the throttle |`throttle_spec_threshold{name="t1", namespace="default",uuid="...",resource="cpu"} 200`     

other metrics exported by [kamon-system-metrics](https://github.com/kamon-io/kamon-system-metrics), [kamon-akka](https://github.com/kamon-io/kamon-akka), [kamon-akka-http](https://github.com/kamon-io/kamon-akka-http) are available.

### `ServiceMonitor` of Prometheus Operator 
Used [prometheus-operator](https://github.com/coreos/prometheus-operator), this repository ships `ServiceMonitor` spec.  So, setup is super easy.

```shell
kubectl create -f prometheus/servicemonitor.yaml
```

# License

Apache License 2.0

# Change Logs

## `0.1.2`
- multi-throttler, multi-scheduler deployment support
  - `throttlerName` is introduced in `Throttle` CRD
  - `throttler-name` and `target-scheduler-names` are introduced in throttler configuration

## `0.1.1`
- fixed returning filter error when normal throttled situation.

## `0.1.0`
first public release.
