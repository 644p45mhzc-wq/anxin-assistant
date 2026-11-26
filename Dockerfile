FROM eclipse-temurin:21-jdk AS build
WORKDIR /build/backend
COPY ["voice-chat-backend 3/.mvn/", ".mvn/"]
COPY ["voice-chat-backend 3/mvnw", "voice-chat-backend 3/pom.xml", "./"]
COPY ["voice-chat-backend 3/src/", "src/"]
COPY ["源代码/", "/build/源代码/"]
RUN sh mvnw -B -ntp package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/backend/target/voice-chat-backend-1.0.0.jar app.jar
ENV SERVER_ADDRESS=0.0.0.0
ENTRYPOINT ["java", "-jar", "app.jar"]
