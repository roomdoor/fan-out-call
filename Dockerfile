FROM eclipse-temurin:25-jre

WORKDIR /app

COPY build/libs/loan-limit-gateway-*.jar /app/app.jar

EXPOSE 8080

# JVM 플래그는 JAVA_TOOL_OPTIONS 환경변수로 전달한다.
#   docker run -e JAVA_TOOL_OPTIONS="-Dkotlinx.coroutines.io.parallelism=192" ...
# 컨테이너 인자는 그대로 Spring 인자가 된다.
#   docker run <image> --app.async-thread-pool.core-pool-size=1700
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
