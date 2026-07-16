FROM node:22-alpine AS build
WORKDIR /src

COPY website/package.json website/package-lock.json website/
RUN cd website && npm ci

# Build from the repository root because the website verifies its screenshots
# against the canonical sources under docs/media/.
COPY . .
RUN cd website && npm run build:production

FROM nginx:1.28-alpine
COPY --from=build /src/website/dist/ /usr/share/nginx/html/
EXPOSE 80
