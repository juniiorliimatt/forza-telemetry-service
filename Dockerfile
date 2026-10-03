# syntax=docker/dockerfile:1

# Versão de Java fixada e única em todo o monorepo (workbox-api, budget-service, notes-service
# e esta imagem) — ver java.toolchain.languageVersion em build.gradle. Mude em todos
# juntos se atualizar.
ARG JAVA_VERSION=25

FROM eclipse-temurin:${JAVA_VERSION}-jdk-alpine AS build
WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
# Cache mount evita rebaixar a distribuição do Gradle (~150MB) e re-resolver as
# dependências Maven Central a cada build.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies || true
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/build/libs/*.jar app.jar
USER app
# 8083 = API REST | 5310/udp = Data Out do Forza (Xbox -> container)
EXPOSE 8083
EXPOSE 5310/udp
ENTRYPOINT ["java", "-jar", "app.jar"]
