# 1. Java 21 (최신 LTS) 공식 경량 이미지 사용
FROM eclipse-temurin:21-jdk-jammy

# 2. 컨테이너 내 작업 폴더 지정
WORKDIR /app

# 3. 빌드된 jar 파일 및 Firebase 키 파일 복사
COPY build/libs/*.jar app.jar
COPY ../firebaseServiceAccountKey.json firebaseServiceAccountKey.json

# 4. 컨테이너에서 열 포트 지정 (Spring Boot 기본 8080)
EXPOSE 8080

# 5. 컨테이너 실행 시 명령
ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200", "-jar", "app.jar"]
