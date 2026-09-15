FROM eclipse-temurin:25-jre

# root로 실행하지 않는다.
RUN useradd --system --create-home --uid 10001 app

WORKDIR /app

COPY build/libs/loan-limit-gateway-*.jar /app/app.jar

USER app

EXPOSE 8080

# DB 접속 정보는 이미지에 들어있지 않다.
# application.yml의 기본값은 jdbc:mysql://localhost:3306 인데, 컨테이너 안에서
# localhost는 컨테이너 자신이다. 둘 중 하나로 띄워야 Flyway 단계에서 죽지 않는다.
#   --network host 로 실행 (게이트웨이와 MySQL이 같은 호스트에 있는 측정 구성)
#   또는 -e SPRING_DATASOURCE_URL=jdbc:mysql://<호스트>:3306/loan_limit_gateway
#
# JVM 플래그는 JAVA_TOOL_OPTIONS 환경변수로 전달한다.
#   docker run -e JAVA_TOOL_OPTIONS="-Dkotlinx.coroutines.io.parallelism=192" ...
# 컨테이너 인자는 그대로 Spring 인자가 된다.
#   docker run <image> --app.async-thread-pool.core-pool-size=1700
#
# --enable-native-access 는 ENTRYPOINT에 직접 둔다. JAVA_TOOL_OPTIONS로 주면
# 측정 스크립트가 같은 변수를 덮어쓸 때 함께 사라진다.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
