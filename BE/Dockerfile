# 1단계: Gradle 빌드
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app
COPY BE/gradlew BE/gradlew
COPY BE/gradle BE/gradle
COPY BE/build.gradle BE/build.gradle
COPY BE/settings.gradle BE/settings.gradle
RUN chmod +x BE/gradlew && cd BE && ./gradlew dependencies --no-daemon -q || true
COPY BE/src BE/src
RUN cd BE && ./gradlew clean build -x test --no-daemon

# 2단계: 실행
FROM eclipse-temurin:21-jdk-jammy
WORKDIR /app
COPY --from=builder /app/BE/build/libs/*.jar app.jar
COPY firebaseServiceAccountKey.json firebaseServiceAccountKey.json
EXPOSE 8080
ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200", "-jar", "app.jar"]
