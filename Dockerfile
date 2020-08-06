FROM gradle:latest as build
WORKDIR /build

COPY . /build

RUN gradle bootJar
COPY /build/build/libs/auto-pin-bot-*.jar /build/app.jar

FROM openjdk:11-jdk-slim

WORKDIR /workspace

COPY --from=build /build/app.jar /workspace/

RUN mkdir data

ENTRYPOINT ["java", "-jar", "app.jar"]
