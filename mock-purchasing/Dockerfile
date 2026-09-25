FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=8082 TZ=UTC
COPY package.json server.js ./
EXPOSE 8082
CMD ["node", "server.js"]
