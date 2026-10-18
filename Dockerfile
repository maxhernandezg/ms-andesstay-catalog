# ---- Etapa 1: compilacion ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Primero el pom para aprovechar la cache de capas de Docker con las dependencias.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Etapa 2: ejecucion ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Usuario sin privilegios: nada corre como root.
RUN addgroup -S andesstay && adduser -S andesstay -G andesstay

COPY --chown=andesstay:andesstay --from=build /build/target/*.jar /app/app.jar

USER andesstay

ENV SPRING_PROFILES_ACTIVE=dev \
    SERVER_PORT=8082 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

EXPOSE 8082

# El healthcheck usa el grupo publico /actuator/health/liveness, que NO incluye el
# indicador de RabbitMQ: si el broker se cae, el catalogo sigue atendiendo su CRUD y
# Docker no tiene por que reiniciarlo (los eventos esperan en q.evt.catalog).
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -q -O - http://localhost:8082/actuator/health/liveness | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
