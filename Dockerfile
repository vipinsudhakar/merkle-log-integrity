# One image for the whole app: Spring Boot serves the API and the built React visualiser from the
# same origin. Built by Render from render.yaml; the build context is the repository root.

# --- 1. Frontend: type-check and build the static bundle -------------------------------------
FROM node:24-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# VITE_API_BASE stays empty: the API is on the same origin.
RUN npm run build

# --- 2. Backend: package the jar, with the frontend inside it as static content --------------
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /app
# Dependencies first, so they are cached between builds when only the code changes.
COPY backend/pom.xml backend/pom.xml
RUN mvn -B -q -f backend/pom.xml dependency:go-offline
COPY backend/src backend/src
# The pom copies docs/benchmarks/results.json onto the classpath (served by GET /api/benchmarks).
COPY docs/benchmarks docs/benchmarks
COPY --from=frontend /app/frontend/dist backend/src/main/resources/static
# Tests run locally and need no database; the build container has none, so they are skipped here.
RUN mvn -B -q -f backend/pom.xml -DskipTests package \
    && cp backend/target/merkle-log-integrity-*.jar /app/app.jar

# --- 3. Runtime: a JRE only, running as a non-root user --------------------------------------
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=backend /app/app.jar app.jar
USER app
EXPOSE 8080
# Sized for Render's free instance (512 MB): cap the heap, use the small serial collector and
# skip the top JIT tier for a faster start. Overridable through JAVA_OPTS.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
