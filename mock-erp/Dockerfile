FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=8081 TZ=UTC
COPY package.json server.js ./
EXPOSE 8081
CMD ["node", "server.js"]
