# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
# Reuse dependencies across source changes and subsequent builds.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -ntp -DskipTests dependency:go-offline
COPY src/main src/main
# CI runs the full test suite before building the image.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -ntp -Dmaven.test.skip=true package
RUN java -Djarmode=tools -jar target/android-0.0.1-SNAPSHOT.jar \
    extract --layers --destination /build/extracted --application-filename app.jar

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN mkdir -p /app/data/books && chown -R 10001:10001 /app/data
# Stable dependencies remain reusable when application classes change.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
