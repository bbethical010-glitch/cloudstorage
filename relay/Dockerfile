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
COPY relay/build.gradle.kts relay/settings.gradle.kts ./relay/
COPY relay/src ./relay/src

# Copy built frontend into the relay resources directory
COPY --from=frontend-build /ui/dist ./relay/src/main/resources/static

# Build the relay module using the wrapper
RUN ./gradlew :relay:installDist --no-daemon

# Stage 3: Minimal Runtime
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the built Ktor application from the module output
# Note the path: relay/build/install/easy-storage-relay
COPY --from=backend-build /workspace/relay/build/install/easy-storage-relay/ ./

# Port for Render
ENV PORT=10000
EXPOSE 10000

# Ensure binary is executable and check it exists
RUN chmod +x ./bin/easy-storage-relay && ls -l ./bin/

CMD ["./bin/easy-storage-relay"]
