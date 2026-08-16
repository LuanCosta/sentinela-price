FROM gradle:8.14.3-jdk21-alpine AS build
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle . .
RUN gradle clean installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /home/gradle/project/build/install/mercado-livre-price-tracker/ ./
USER app
EXPOSE 8080
CMD ["bin/mercado-livre-price-tracker"]

