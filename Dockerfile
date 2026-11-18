FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests clean package

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 -m app
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=10 \
  CMD curl -fs http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]