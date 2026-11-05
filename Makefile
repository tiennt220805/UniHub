# Operating the Kubernetes proof of concept (Phase 7; deployment-and-cicd.md §2–§4). Everything runs from the
# repository root. Kustomize is the one built into kubectl (no separate binary, C49).
#
#   make secrets                  create the Secret from the environment (k8s/scripts/create-secrets.sh)
#   make deploy-dev TAG=<sha>     migrate, then deploy overlays/dev with the images built for that commit
#   make deploy-loadtest TAG=<sha>
#   make render PROFILE=dev       print what would be applied (no cluster needed)
#   make check-images TAG=<sha>   check that both images exist for that tag, print their digests
#
# TAG is the commit whose CI run built and pushed the images (the `build-and-push` job tags them with the full
# SHA). It defaults to the current commit, which only exists on Docker Hub once that commit has been pushed and
# its CI run has finished: a commit made here and not pushed has no image, and the pods would not start.
# `latest` is never deployed (D84).

# pipefail: a failed `kubectl kustomize` must not be hidden by the `sed` or `kubectl apply` after it.
SHELL := /bin/bash -o pipefail

NAMESPACE ?= unihub
PROFILE   ?= dev
TAG       ?= $(shell git rev-parse HEAD)
REGISTRY  ?= thanhtien2208

# Kustomize refuses files outside the kustomization root by default; the generators read the migrations
# (apps/backend/src/main/resources/db/migration) and observability/, so the build relaxes that restriction.
KBUILD = kubectl kustomize --load-restrictor LoadRestrictionsNone

# The manifests carry @IMAGE_TAG@ where the image tag goes, so a base applied without this step fails loudly
# (an invalid image name) instead of quietly running whatever `latest` happens to be.
define RENDER
$(KBUILD) k8s/overlays/$(1) | sed 's|@IMAGE_TAG@|$(TAG)|g'
endef

.PHONY: secrets render check-images pre-migration migrate deploy-dev deploy-loadtest rollout

secrets:
	k8s/scripts/create-secrets.sh

render:
	@$(call RENDER,$(PROFILE))

check-images:
	@case "$(TAG)" in latest|"") echo "TAG must be a commit SHA, not '$(TAG)'" >&2; exit 1;; esac
	@for image in unihub-backend unihub-web; do \
	  digest=$$(docker buildx imagetools inspect $(REGISTRY)/$$image:$(TAG) --format '{{.Manifest.Digest}}') \
	    || { echo "$(REGISTRY)/$$image:$(TAG) does not exist (has CI finished for that commit?)" >&2; exit 1; }; \
	  echo "$(REGISTRY)/$$image:$(TAG) $$digest"; \
	done

# Postgres Master and the generated ConfigMaps first, nothing else: the API must not start against an unmigrated
# schema (architecture.md §6.2). Only objects labelled unihub.io/pre-migration=true are applied here, rendered from
# the same overlay the deploy target applies afterwards, so that later full apply changes nothing.
# `pg_isready` loop: a Running pod is not yet a pod that accepts connections.
pre-migration:
	kubectl apply -f k8s/namespace.yaml
	@kubectl get secret backend-api-secrets -n $(NAMESPACE) >/dev/null 2>&1 \
	  || { echo "backend-api-secrets does not exist: run 'make secrets' first" >&2; exit 1; }
	$(call RENDER,$(PROFILE)) | kubectl apply -f - -l unihub.io/pre-migration=true
	@for i in $$(seq 1 60); do \
	  kubectl exec -n $(NAMESPACE) postgres-master-0 -c postgres -- sh -c 'pg_isready -q -U "$$POSTGRES_USER" -d "$$POSTGRES_DB"' 2>/dev/null && exit 0; \
	  sleep 2; \
	done; echo "postgres-master did not accept connections within 120s" >&2; exit 1

# A Job's spec is immutable: the old one is deleted before the new one is applied. The deploy targets stop here
# when Flyway fails: the log of the failed Job is printed and nothing else is applied.
migrate: pre-migration
	kubectl delete job/flyway-migrate -n $(NAMESPACE) --ignore-not-found
	kubectl apply -f k8s/jobs/flyway-migrate-job.yaml
	@kubectl wait --for=condition=complete --timeout=180s job/flyway-migrate -n $(NAMESPACE) \
	  || { echo "flyway-migrate did not complete; its log:" >&2; \
	       kubectl logs job/flyway-migrate -n $(NAMESPACE) -c flyway --tail=50 >&2; exit 1; }

deploy-dev: PROFILE = dev
deploy-dev: check-images migrate
	$(call RENDER,dev) | kubectl apply -f -
	@$(MAKE) --no-print-directory rollout PROFILE=dev

deploy-loadtest: PROFILE = loadtest
deploy-loadtest: check-images migrate
	$(call RENDER,loadtest) | kubectl apply -f -
	@$(MAKE) --no-print-directory rollout PROFILE=loadtest

# Waits for every workload the overlay creates, so `make deploy-*` returns only when the pods are Ready. The list
# is written out: KubernetesManifestsTest fails when it differs from the Deployments and StatefulSets of the
# overlay's render. overlays/dev runs no Postgres replica (0 replicas), so it is not waited for there.
WORKLOADS_COMMON = deployment/backend-api deployment/web deployment/redis deployment/rabbitmq deployment/minio \
  deployment/mailpit deployment/payment-gateway-stub deployment/ai-summarization-stub statefulset/postgres-master
WORKLOADS_dev      = $(WORKLOADS_COMMON)
WORKLOADS_loadtest = $(WORKLOADS_COMMON) statefulset/postgres-replica

rollout:
	@for w in $(WORKLOADS_$(PROFILE)); do \
	  kubectl rollout status $$w -n $(NAMESPACE) --timeout=300s || exit 1; \
	done
