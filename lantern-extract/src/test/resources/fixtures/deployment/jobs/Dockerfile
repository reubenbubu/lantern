FROM eclipse-temurin:21-jre
ENV JDK_JAVA_OPTIONS=-Djobs.mode=batch
CMD ["--jobs.ignored=true"]
ENTRYPOINT java -jar /jobs.jar --spring.profiles.active=jobs
