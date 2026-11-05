# apps/web/Dockerfile — build context: the REPOSITORY ROOT (deployment-and-cicd.md §1; Phase 7 checklist D85)
#
#   docker build -f apps/web/Dockerfile -t unihub-web:dev .
#   docker build -f apps/web/Dockerfile --build-arg VITE_PAYMENT_TEST_MODE=true -t unihub-web:dev .
#
# The root is the context because the build generates its API types from packages/api-contract
# (`npm run build` runs `api:types` first); the root .dockerignore lets in only that and apps/web.
# Base images are pinned by digest of the multi-platform index (linux/amd64 and linux/arm64); re-run
# `docker buildx imagetools inspect <tag>` before moving a pin.

# ---- Builder stage: the build machine's own architecture, so npm and Vite never run under QEMU ----
# node:22-bookworm-slim (glibc: the lockfile's native binaries for rollup, tailwind and lightningcss are the
# ones CI already installs on Linux/glibc). Node 22 is what the `web` CI job uses.
FROM --platform=$BUILDPLATFORM node@sha256:4b150b48b4116e2a22f2c39cc27025fbf4f153e2078fd3b14d159fdb5c7279da AS builder

# Whether the build offers the Stripe test-token dropdown on checkout. Off by default: only an image meant
# for the sandbox asks for it (the Minikube image passes true, because its payment gateway is a stub).
# A flag, not a secret: it ends up in the public bundle.
ARG VITE_PAYMENT_TEST_MODE=false
# Not an ARG on purpose: VITE_API_BASE_URL stays empty, the API is the same origin.

# The relative layout is kept: apps/web's `api:types` reads ../../packages/api-contract/openapi.yaml.
WORKDIR /src/apps/web

# Dependency layer. No NODE_ENV=production here: the build tools are devDependencies.
COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci

COPY packages/api-contract /src/packages/api-contract
COPY apps/web ./
RUN VITE_PAYMENT_TEST_MODE=$VITE_PAYMENT_TEST_MODE npm run build

# ---- Runtime stage: nginx, non-root (uid 101), port 8080 ----
# nginxinc/nginx-unprivileged:1.28-alpine
FROM nginxinc/nginx-unprivileged@sha256:7377697a821c131a924a7105fafbe7414db4e9fcc77a6f08f776f33f141ec3f8 AS runtime

COPY apps/web/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /src/apps/web/dist /usr/share/nginx/html

EXPOSE 8080
