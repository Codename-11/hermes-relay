FROM node:22-alpine AS website-build
WORKDIR /src

COPY website/package.json website/package-lock.json website/
RUN cd website && npm ci

# Build from the repository root because the website verifies its screenshots
# against the canonical sources under docs/media/.
COPY . .
RUN cd website && npm run build:production

FROM node:24-alpine AS docs-build
WORKDIR /src

COPY user-docs/package.json user-docs/package-lock.json user-docs/
RUN cd user-docs && npm ci

COPY user-docs user-docs
COPY gradle gradle
COPY preview preview
RUN cd user-docs && npm run build

FROM nginx:1.28-alpine
COPY website/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=website-build /src/website/dist/ /usr/share/nginx/html/
COPY --from=docs-build /src/user-docs/.vitepress/dist/ /usr/share/nginx/html/docs/
EXPOSE 80
