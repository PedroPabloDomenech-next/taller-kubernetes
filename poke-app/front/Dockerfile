FROM node:20-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . ./

ARG VITE_API_BASE_URL=http://localhost:5180/api/v2
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

ARG VITE_AUTH_BASE_URL=http://localhost:5190
ENV VITE_AUTH_BASE_URL=${VITE_AUTH_BASE_URL}

RUN npm run build

FROM nginx:1.27-alpine AS runtime
WORKDIR /usr/share/nginx/html

COPY --from=build /app/dist ./

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
