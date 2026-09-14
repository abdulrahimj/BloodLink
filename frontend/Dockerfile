# Multi-stage build: Node is only needed to build the static React bundle,
# not to serve it — the finished image ships plain static files behind nginx,
# which is smaller and simpler than running a Node server in production.
#
# Stage 1 — build.
FROM node:20-alpine AS build
WORKDIR /app

# Copy just the manifest files first so the npm install layer is cached
# independently of source changes.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# VITE_API_BASE_URL is read by Vite at BUILD time (import.meta.env), not at
# container start time — React ships as static JS with no server behind it to
# read environment variables from later, so this must be a build arg, not a
# plain runtime `environment:` entry in docker-compose.yml. Defaults to how the
# backend is reached from the *browser* (the host's published port), not the
# Docker-network hostname the backend container uses — a browser tab on the
# host machine cannot resolve the compose network's internal "backend" name.
ARG VITE_API_BASE_URL=http://localhost:8080
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# Stage 2 — serve the built static files with nginx.
FROM nginx:alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html

# A single-page app needs every unmatched path to fall back to index.html so
# client-side routing (e.g. a refresh on a deep link) doesn't 404 at nginx.
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
