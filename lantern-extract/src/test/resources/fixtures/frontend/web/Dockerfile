FROM node:22-alpine AS build
WORKDIR /app
COPY . .
RUN npm ci && npm run build
EXPOSE 4200

FROM nginx:1.27-alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/web/browser /usr/share/nginx/html
EXPOSE 80
