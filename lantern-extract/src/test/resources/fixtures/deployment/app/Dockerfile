ARG JAVA=21
FROM maven:3.9 AS build
COPY . /src
RUN mvn -f /src/pom.xml package
FROM eclipse-temurin:${JAVA}-jre AS runtime
FROM runtime
COPY --from=build /src/target/app.jar /app.jar
EXPOSE 8080 \
  9090
ENTRYPOINT ["java", "-jar", "/app.jar"]
