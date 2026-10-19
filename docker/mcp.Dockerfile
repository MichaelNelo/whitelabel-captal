# The MCP backoffice server.
#
# One stage, unlike `Dockerfile.api`, and deliberately: that one compiles inside the image, which
# means every build re-resolves the whole dependency tree and the image needs a JDK and a `mill`
# wrapper to exist in the context. Here the jar is built by Mill where Mill already lives and the
# image only packages it. `mcp.image` stages this file beside `mcp.jar` and runs the build.
FROM eclipse-temurin:21-jre-noble

WORKDIR /app

# Staged by `mcp.image` from `out/mcp/assembly.dest/out.jar`, rather than referenced at that path:
# a Dockerfile reaching into Mill's output layout breaks the first time the layout changes, and the
# build context would have to be the whole repository.
COPY mcp.jar mcp.jar

# Matches the `container_port` the Terraform module passes and the target group's health check.
EXPOSE 8080
ENV MCP_PORT=8080

# Same reasoning as the API image, and it matters more here. rqlite is reached through ECS service
# discovery, so its address is a DNS name whose answer changes when the task rotates -- a Fargate
# redeploy, an OOM kill. The JDK caches a positive lookup for 30 seconds by default and negatives
# for 10, which leaves the JDBC client pinned to an address that is gone. Five seconds and no
# negative caching makes it heal on its own instead of staying down until the JVM re-resolves.
#
# `-Xss4m` because Quill's query generation is a macro expansion deep enough to overflow the
# default stack on some of these queries.
ENV JAVA_TOOL_OPTIONS="-Xss4m -Dnetworkaddress.cache.ttl=5 -Dnetworkaddress.cache.negative.ttl=0"

# Migrations first, then the server, in one command so a failed migration stops the container
# rather than starting a server against a schema it was not written for.
#
# `McpMigrate` and not `infra.Migrate`: there are two schemas with two history tables, and this
# owns `db/migration-mcp` only. It also refuses to start when the API schema is behind the version
# its tools are written against -- deployment order between two services is not something either
# one can enforce, so it says so at startup instead of failing one operator at a time on a column
# that is not there.
#
# Both read `database.jdbcUrl`, which `application.conf` overrides from `DB_URL`.
CMD ["sh", "-c", "java -cp mcp.jar whitelabel.captal.mcp.McpMigrate && java -jar mcp.jar"]
