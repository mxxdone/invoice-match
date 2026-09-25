const http = require("node:http");

function handler(request, response) {
  response.setHeader("content-type", "application/json; charset=utf-8");
  if (request.method === "GET" && request.url === "/health") {
    response.writeHead(200);
    response.end(JSON.stringify({ status: "UP" }));
    return;
  }
  response.writeHead(404);
  response.end(JSON.stringify({ error: "Not found" }));
}

if (require.main === module) {
  const port = Number(process.env.PORT || 8081);
  http.createServer(handler).listen(port, "0.0.0.0");
}

module.exports = { handler };
