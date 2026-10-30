FROM eclipse-temurin:21-jre AS base
ENV SPRING_PROFILES_ACTIVE=worker JAVA_OPTS="-Dworker.threads=4"
CMD ["--worker.batch=50"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS --add-opens=java.base/java.lang=ALL-UNNAMED -jar /worker.jar -Dworker.ignored=1 \"$@\"", "--"]
FROM base
COPY worker.jar /worker.jar
