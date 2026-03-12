FROM gradle:8.7.0-jdk17 AS build

WORKDIR /workspace
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle --no-daemon installDist

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /workspace/build/install/relay/ ./

ENV PORT=10000
EXPOSE 10000

CMD ["./bin/relay"]
