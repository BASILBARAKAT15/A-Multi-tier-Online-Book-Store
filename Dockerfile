FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/target/catalog-service-1.0-SNAPSHOT.jar /app/app.jar

COPY --from=build /app/src/main/resources /app/src/main/resources

ENV SERVICE_CLASS=CatalogService

CMD ["sh", "-c", "java -cp /app/app.jar org.example.${SERVICE_CLASS}"]