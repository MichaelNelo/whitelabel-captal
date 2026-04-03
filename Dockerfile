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

# Build-time args
ARG SERVER_DEV_ENDPOINTS=false

# Increase stack size for Scala 3 compiler (babel-generic inline derivations)
ENV JAVA_TOOL_OPTIONS="-Xss4m"

# Build assembly JARs
RUN ./mill api.assembly && ./mill infra.assembly

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-noble

WORKDIR /app

# Re-declare ARGs after FROM
ARG SERVER_DEV_ENDPOINTS=false

# Copy assembly JARs
COPY --from=builder /app/out/api/assembly.dest/out.jar api.jar
COPY --from=builder /app/out/infra/assembly.dest/out.jar infra.jar

# Create provision directory (populated at deploy time via volume mount or COPY in derived image)
RUN mkdir -p /etc/captal

# Expose port
EXPOSE 8080

# Environment variables
ENV SERVER_DEV_MODE=false
ENV SERVER_DEV_ENDPOINTS=${SERVER_DEV_ENDPOINTS}
ENV SERVER_HOST=0.0.0.0
ENV SERVER_PORT=8080

# Run migrations and start server (provisioning happens at server startup)
CMD java -cp infra.jar whitelabel.captal.infra.Migrate && \
    java -jar api.jar
