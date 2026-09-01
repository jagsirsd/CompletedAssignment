FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /workspace/app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src src
RUN mvn package -DskipTests

FROM eclipse-temurin:17-jre-jammy
VOLUME /tmp
COPY --from=build /workspace/app/target/*.jar app.jar
EXPOSE 8080 9090
ENTRYPOINT ["java","-jar","/app.jar"]
