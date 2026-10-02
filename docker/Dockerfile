# syntax=docker/dockerfile:1
# Build the canonical JAR first: ./gradlew bootJar (or use buildImage).
FROM docker:29.5.3-cli AS docker-cli
FROM eclipse-temurin:21-jre-ubi9-minimal AS jvm-runtime
ARG SOURCE_REPOSITORY=https://github.com/edigonzales/netl-mcp
ARG IMAGE_VERSION=local
ARG GIT_COMMIT=unknown
LABEL org.opencontainers.image.source="${SOURCE_REPOSITORY}" \
      org.opencontainers.image.title="netl-mcp" \
      org.opencontainers.image.description="Spring AI MCP server (Java 21)" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.revision="${GIT_COMMIT}"
WORKDIR /application
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
RUN mkdir -p /workspace && chown 1001:0 /application /workspace && chmod g=u /application /workspace
COPY --chown=1001:0 build/libs/netl-mcp.jar /application/application.jar
ENV SPRING_PROFILES_ACTIVE=http
USER 1001:0
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/application/application.jar"]
CMD ["mcp"]
