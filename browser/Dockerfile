FROM node:22-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends chromium xvfb xauth fonts-liberation tzdata \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY browser/worker.mjs ./browser/worker.mjs
COPY src/main/resources/web/login-helper.mjs ./src/main/resources/web/login-helper.mjs
ENV HOME=/tmp DISPLAY=:99 DOCKDROPS_CONTAINER_BROWSER=true
USER node
CMD ["xvfb-run", "--server-args=-screen 0 1100x850x24 -nolisten tcp", "node", "browser/worker.mjs"]
