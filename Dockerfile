FROM gradle:latest as build
WORKDIR /build

COPY . /build

RUN gradle bootJar
COPY /build/build/libs/app.jar /build/app.jar

FROM openjdk:11-jdk-slim

WORKDIR /workspace

COPY --from=build /build/app.jar /workspace/

RUN mkdir /workspace/data

ENTRYPOINT ["java", "-jar", "app.jar"]
