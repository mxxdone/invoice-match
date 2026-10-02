# Upstream's security release is source-only. Do not use the older Docker tag.
# https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z
FROM golang:1.24.8-bookworm AS build
ENV CGO_ENABLED=0 GOTOOLCHAIN=local
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go install -v github.com/minio/minio@RELEASE.2025-10-15T17-29-55Z
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go install -v github.com/minio/mc@RELEASE.2025-04-16T18-13-26Z

FROM alpine:3.22
RUN apk add --no-cache ca-certificates curl && addgroup -g 10001 storage && \
    adduser -D -u 10001 -G storage storage && mkdir /data && chown storage:storage /data
COPY --from=build /go/bin/minio /go/bin/mc /usr/local/bin/
USER 10001:10001
EXPOSE 9000 9001
ENTRYPOINT ["minio"]
CMD ["server", "/data", "--console-address", ":9001"]
