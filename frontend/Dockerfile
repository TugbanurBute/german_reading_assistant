# syntax=docker/dockerfile:1

# --- Build: type check and Vite production build ---
FROM node:22-alpine AS build
WORKDIR /app

# Dependencies first: reused as long as the lock file doesn't change.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# No VITE_API_BASE_URL: the bundle calls "/api" on its own origin (proxied by Nginx), no localhost URL.
RUN npm run build

# --- Runtime: unprivileged Nginx (runs as a non-root user, listens on 8080) ---
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
    CMD wget -qO- http://localhost:8080/ > /dev/null || exit 1
