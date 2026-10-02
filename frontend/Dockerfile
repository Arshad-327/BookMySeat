# No "# syntax=" line, on purpose. It makes every build fetch the Dockerfile frontend
# from Docker Hub first - even a fully cached one - and the first cold start of this
# stack failed on exactly that request timing out. The cache mount below is supported by
# the frontend built into Docker 23 and later, so the line bought nothing.
#
# The frontend as it would be shipped: a built bundle behind a static server. No Node at
# run time, and none needed on the machine of whoever runs `docker compose up`.
#
# Build context is frontend/ (unlike the services, which build from the repository root).

# ---------- stage 1: build the bundle ----------
FROM node:24-alpine AS build
WORKDIR /app

# The lockfile first, so `npm ci` is re-run only when dependencies change.
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

COPY . .

# WHERE THE API IS, AS THE BROWSER SEES IT. This is baked into the bundle at build time -
# Vite replaces import.meta.env.VITE_API_BASE_URL with a literal - and it is the address
# the user's browser will call, on the user's machine. It is NOT a container address:
# "http://api-gateway:8080" means nothing to a browser. Compose publishes the gateway on
# localhost:8080, and that is what goes here.
ARG VITE_API_BASE_URL=http://localhost:8080
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

# tsc --noEmit, then vite build. A type error fails the image build.
RUN npm run build

# ---------- stage 2: serve it ----------
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
