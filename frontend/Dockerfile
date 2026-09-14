# syntax=docker/dockerfile:1
#
# Multi-stage, so no Node is needed on the reviewer's machine - the same promise the
# backend image makes.

FROM node:22-alpine AS build
WORKDIR /build

# Manifest first, sources second: a source change reuses the installed dependency
# layer rather than reinstalling everything.
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

COPY tsconfig*.json vite.config.ts index.html ./
COPY src/ src/

# Type check and tests run here, not as a separate step someone might skip. A type error
# or a broken optimistic update should fail the image build rather than ship.
#
# `tsc -b`, not `tsc --noEmit`: tsconfig.json is solution-style (`files: []` plus project
# references), so --noEmit compiles nothing and always succeeds. It looked like a gate and
# was not one.
#
# Note the contrast with the backend image, which deliberately skips its tests: those use
# Testcontainers and need a Docker daemon this build does not have. These need nothing but
# Node, so there is no reason not to run them.
RUN npx tsc -b && npx vitest run && npx vite build

FROM nginx:1.27-alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
