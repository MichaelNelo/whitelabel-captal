# Stage 1: Build
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Copy Mill and build definition (for better caching)
COPY mill build.mill .mill-version ./
RUN chmod +x mill

# Copy source code
COPY core/ core/
COPY endpoints/ endpoints/
COPY infra/ infra/
COPY api/ api/
COPY client/ client/

# Build-time args (defaults to production mode)
ARG SERVER_DEV_MODE=false
ARG SERVER_DEV_ENDPOINTS=false

# Increase stack size for Scala 3 compiler (babel-generic inline derivations)
ENV JAVA_TOOL_OPTIONS="-Xss4m"

# Build assembly JARs
RUN ./mill api.assembly && ./mill infra.assembly

# Build client only in dev mode (in prod, nginx serves static files)
# Create placeholder dir so COPY won't fail in prod mode
RUN mkdir -p /app/out/client/fullLinkJS.dest && \
    if [ "$SERVER_DEV_MODE" = "true" ]; then \
      ENVIRONMENT=dev ./mill client.fullLinkJS; \
    fi

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-noble

WORKDIR /app

# Re-declare ARGs after FROM
ARG SERVER_DEV_MODE=false
ARG SERVER_DEV_ENDPOINTS=false

# Copy assembly JARs
COPY --from=builder /app/out/api/assembly.dest/out.jar api.jar
COPY --from=builder /app/out/infra/assembly.dest/out.jar infra.jar

# Copy client assets (needed in dev mode; harmless in prod)
COPY --from=builder /app/out/client/fullLinkJS.dest/ out/client/fullLinkJS.dest/
COPY client/index.prod.html client/index.html
COPY client/assets/ client/assets/

# Expose port
EXPOSE 8080

# Environment variables
ENV SERVER_DEV_MODE=${SERVER_DEV_MODE}
ENV SERVER_DEV_ENDPOINTS=${SERVER_DEV_ENDPOINTS}
ENV SERVER_HOST=0.0.0.0
ENV SERVER_PORT=8080

# Run migrations, seed, and start server
CMD java -cp infra.jar whitelabel.captal.infra.Migrate && \
    java -cp infra.jar whitelabel.captal.infra.Seed && \
    java -jar api.jar
