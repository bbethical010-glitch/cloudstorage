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

# Stage 2: Build Ktor Backend
FROM gradle:8.7.0-jdk17 AS backend-build
WORKDIR /workspace

# Copy gradle config
COPY relay/build.gradle.kts relay/settings.gradle.kts ./
# Copy backend source
COPY relay/src ./src

# Copy built frontend into the Ktor resources directory BEFORE building
COPY --from=frontend-build /ui/dist ./src/main/resources/static

# Build the complete bundled application
RUN gradle --no-daemon installDist

# Stage 3: Minimal Runtime
FROM eclipse-temurin:17-jre
WORKDIR /app
# Copy the built Ktor application (which now contains the React UI inside its JAR/classpath)
COPY --from=backend-build /workspace/build/install/easy-storage-relay/ ./

ENV PORT=10000
EXPOSE 10000

CMD ["./bin/easy-storage-relay"]
