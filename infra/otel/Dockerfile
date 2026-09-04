FROM otel/opentelemetry-collector-contrib:0.112.0
# busybox's wget is statically linked, so it runs fine even though the base
# image has no shell/package manager of its own.
COPY --from=busybox:1.36-musl /bin/wget /bin/wget