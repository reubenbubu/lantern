ARG JAVA=21
FROM maven:3.9 AS build
ENV MAVEN_OPTS=-Xmx1g
COPY . /src
RUN mvn -f /src/pom.xml package
FROM eclipse-temurin:${JAVA}-jre AS runtime
FROM runtime
COPY --from=build /src/target/app.jar /app.jar
EXPOSE 8080 \
  9090
ENV SPRING_PROFILES_ACTIVE=prod JAVA_TOOL_OPTIONS="-Dserver.port=8081 -Xss512k"
ENV LEGACY_MODE on
ENTRYPOINT ["java", "-Dapp.api-key=abc123", "-jar", "/app.jar", "--spring.datasource.url=jdbc:postgresql://db:5432/orders"]
