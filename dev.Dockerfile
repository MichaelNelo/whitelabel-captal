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

# Build assembly JARs and client
RUN ENVIRONMENT=dev ./mill api.assembly && \
    ./mill infra.assembly && \
    ENVIRONMENT=dev ./mill client.fastLinkJS

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-noble

WORKDIR /app

# Copy assembly JARs
COPY --from=builder /app/out/api/assembly.dest/out.jar api.jar
COPY --from=builder /app/out/infra/assembly.dest/out.jar infra.jar

# Copy client assets
COPY --from=builder /app/out/client/fastLinkJS.dest/ out/client/fastLinkJS.dest/
COPY client/index.html client/index.html
COPY client/assets/ client/assets/

# Expose port
EXPOSE 8080

# Environment variables for dev mode
ENV SERVER_DEV_MODE=true
ENV SERVER_HOST=0.0.0.0
ENV SERVER_PORT=8080

# Run migrations, seed, and start server
CMD java -cp infra.jar whitelabel.captal.infra.Migrate && \
    java -cp infra.jar whitelabel.captal.infra.Seed && \
    java -jar api.jar
