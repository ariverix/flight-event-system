# =============================================================================
# Stage 1: Build — React 18 / TypeScript 5 / Vite
# =============================================================================
FROM node:20-alpine AS builder
WORKDIR /app

# Копируем зависимости отдельным слоем для кеширования.
# Слой пересобирается только при изменении package*.json.
COPY package*.json ./
RUN npm ci --ignore-scripts

# Копируем исходники и собираем.
# VITE_* переменные, заданные здесь как ARG, запекаются в JS-бандл при сборке.
# Для runtime-конфигурации используется window.__env__ (entrypoint.sh / ConfigMap в K8s).
ARG VITE_WS_URL=""
ENV VITE_WS_URL=${VITE_WS_URL}

COPY . .
RUN npm run build

# =============================================================================
# Stage 2: Runtime — nginx:alpine (slim)
# Отдаёт собранный SPA (dist/) как статику.
# Секретов не хранит; конфигурация монтируется через ConfigMap (K8s) или env (Compose).
# =============================================================================
FROM nginx:1.27-alpine-slim AS runtime

# Версионирование встроенного nginx для аудита
LABEL org.opencontainers.image.title="eca-frontend"
LABEL org.opencontainers.image.description="ECA System — React SPA served by nginx"
LABEL org.opencontainers.image.vendor="ФГУП ЗащитаИнфоТранс"

# Убираем дефолтный конфиг (будет заменён нашим)
RUN rm -f /etc/nginx/conf.d/default.conf

# Копируем собранный SPA из стадии builder
COPY --from=builder /app/dist /usr/share/nginx/html

# Копируем nginx-конфиг и entrypoint
# nginx.conf может быть переопределён через ConfigMap-mount в K8s
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY entrypoint.sh /entrypoint.sh

# Делаем html-директорию доступной для записи nginx-пользователем (uid 101).
# entrypoint.sh записывает env.js в эту директорию.
RUN chmod +x /entrypoint.sh \
    && chown -R nginx:nginx /usr/share/nginx/html \
    && chown -R nginx:nginx /var/cache/nginx \
    && chown -R nginx:nginx /var/log/nginx \
    && touch /var/run/nginx.pid \
    && chown nginx:nginx /var/run/nginx.pid

# Запуск от непривилегированного пользователя.
# nginx master требует root для bind :80; используем nginx uid=101 как компромисс:
# master стартует как nginx (благодаря capabilities), worker — тоже nginx.
# Альтернатива для строгого non-root: nginxinc/nginx-unprivileged + listen 8080.
USER nginx

EXPOSE 80

ENTRYPOINT ["/entrypoint.sh"]
