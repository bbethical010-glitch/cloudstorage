# Stage 1: Build React Frontend
FROM node:20-alpine AS frontend-build
WORKDIR /ui
# Copy package files for layer caching
COPY ui/package.json ui/package-lock.json* ./
RUN npm install
# Copy the rest of the frontend source
COPY ui/ ./
# Build the Vite React app
RUN npm run build

# Stage 2: Build Ktor Backend (Multi-Module Context)
FROM eclipse-temurin:17-jdk AS backend-build
WORKDIR /workspace

# Copy Gradle wrapper and root configuration
COPY gradlew ./
COPY gradle/ ./gradle/
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

# Copy the relay module source and config
COPY relay/build.gradle.kts relay/src ./relay/src
# We don't copy relay/settings.gradle.kts because it's ignored in multi-module builds

# COPY built frontend into the relay resources directory
COPY --from=frontend-build /ui/dist ./relay/src/main/resources/static

# Build the relay module using the wrapper
# This creates output in relay/build/install/relay/
RUN ./gradlew :relay:installDist --no-daemon

# Stage 3: Minimal Runtime
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the built Ktor application from the module output
# Note: In multi-module builds, the folder name is the module name (relay)
COPY --from=backend-build /workspace/relay/build/install/relay/ ./

# Port for Render
ENV PORT=10000
EXPOSE 10000

# Ensure binary is executable and check it exists
# Note: The binary is named 'relay'
RUN chmod +x ./bin/relay && ls -l ./bin/

CMD ["./bin/relay"]
