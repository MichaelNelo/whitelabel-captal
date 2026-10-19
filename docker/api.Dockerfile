# The API every location's service runs.
#
# One stage, and that is a repair rather than a preference. This compiled inside the image: it
# copied the sources in and ran `./mill api.assembly` in a builder stage, which stopped working
# twice over without anyone noticing, because nothing builds this image in CI.
#
#   - `COPY provision/` named a directory deleted with the declarative provisioning system.
#   - `COPY mill` named a wrapper script that is not in this repository.
#
# Either one fails the build on the line that touches it. Mill builds the jar where Mill already
# lives now and the image only packages it — `api.image` stages this file beside `api.jar`, so the
# build context is two files instead of the whole repository and no dependency tree is re-resolved
# per image.
FROM eclipse-temurin:21-jre-noble

WORKDIR /app

# Staged by `api.image` from `out/api/assembly.dest/out.jar`, rather than referenced at that path: a
# Dockerfile reaching into Mill's output layout breaks the first time the layout changes.
COPY api.jar api.jar

# Written to when a location overrides an asset; the directory has to exist before the first write
# rather than after it.
RUN mkdir -p /etc/captal

EXPOSE 8080

ARG SERVER_DEV_ENDPOINTS=false
ENV SERVER_DEV_MODE=false
ENV SERVER_DEV_ENDPOINTS=${SERVER_DEV_ENDPOINTS}
ENV SERVER_HOST=0.0.0.0
ENV SERVER_PORT=8080

# DNS cache TTL tuning for Fargate and ECS service discovery.
#
# The JDK caches a positive lookup for 30 seconds by default and negatives for 10. rqlite is reached
# through a service-discovery name whose answer changes when its task rotates — a redeploy, an OOM
# kill — and the JDBC client stays pinned to an address that is gone. Five seconds with no negative
# caching lets this heal on its own instead of staying down until the JVM re-resolves.
ENV JAVA_TOOL_OPTIONS="-Xss4m -Dnetworkaddress.cache.ttl=5 -Dnetworkaddress.cache.negative.ttl=0"

# Migrations first, then the server, in one command so a failed migration stops the container rather
# than starting a server against a schema it was not written for.
#
# `Migrate` owns `db/migration` and the default history table; the backoffice has its own and runs
# `McpMigrate` from its own image. Both take `Migrate.Lock` first, which matters most here: every
# location runs this image, so a fleet redeploy is one migrator per location against one history
# table, and Flyway cannot serialise them on SQLite.
CMD ["sh", "-c", "java -cp api.jar whitelabel.captal.platform.Migrate && java -jar api.jar"]
