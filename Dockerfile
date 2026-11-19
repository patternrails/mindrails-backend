FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY gradlew build.gradle settings.gradle ./
COPY gradle/wrapper/ gradle/wrapper/
COPY src/main/ src/main/
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar

FROM public.ecr.aws/docker/library/eclipse-temurin:21-jre-jammy
LABEL org.opencontainers.image.title="MindRails Backend" \
      org.opencontainers.image.source="https://github.com/patternrails/mindrails-backend"
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git ripgrep \
    && rm -r /var/lib/apt/lists \
    && groupadd --gid 10001 mindrails \
    && useradd --uid 10001 --gid 10001 --create-home mindrails \
    && install -d -o mindrails -g mindrails /opt/mindrails/data
WORKDIR /opt/mindrails
COPY --from=build /build/build/libs/mindrails-backend.jar ./mindrails-backend.jar
ENV MINDRAILS_ERROR_LOG_FILE=/tmp/mindrails-error.log
USER mindrails
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/mindrails/mindrails-backend.jar"]
