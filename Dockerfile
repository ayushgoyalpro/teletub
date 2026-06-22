FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM mcr.microsoft.com/playwright/java:v1.52.0-noble
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 7001
ENTRYPOINT ["java", "-jar", "app.jar"]