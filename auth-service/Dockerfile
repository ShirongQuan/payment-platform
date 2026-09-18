# syntax=docker/dockerfile:1.7

########## Build stage ##########
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Copy parent pom (needed for reactor/dependency management)
COPY pom.xml pom.xml

# Copy pom files first for better layer caching
COPY auth-service/pom.xml auth-service/pom.xml
COPY shared-spring-lib/pom.xml shared-spring-lib/pom.xml

# auth-service depends on the shared-spring-lib module, which isn't published to
# any remote repository. Install the parent/root POM into the local repo
# directly (avoids needing the full reactor with all sibling modules present),
# then build and install shared-spring-lib against it.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -N org.apache.maven.plugins:maven-install-plugin:3.1.1:install-file \
        -Dfile=pom.xml -DgroupId=org.example -DartifactId=payment-platform \
        -Dversion=1.0-SNAPSHOT -Dpackaging=pom

COPY shared-spring-lib/src shared-spring-lib/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f shared-spring-lib/pom.xml -B -q -DskipTests install

# Pre-fetch dependencies (cache-friendly)
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f auth-service/pom.xml -B -q dependency:go-offline

# Copy source and build
COPY auth-service/src auth-service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f auth-service/pom.xml -B -DskipTests clean package

########## Runtime stage ##########
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# curl is needed for the docker-compose HEALTHCHECK (hits /actuator/health).
# Not present by default on this base image.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Security: run as non-root
RUN useradd -r -u 1001 spring
USER 1001

# Copy built jar (adjust if your artifact name differs)
COPY --from=build /workspace/auth-service/target/*.jar /app/app.jar

EXPOSE 9000
ENV JAVA_OPTS=""

ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]