FROM openjdk:16.0.1-slim


ARG PROMETHEUS_VERSION=0.12.0

ENV java_option -javaagent:/kafka-tracing-aggregator/jmx_prometheus_javaagent.jar=8080:/kafka-tracing-aggregator/config/prometheus-exporter.yml
ENV PATH ${PATH}:/kafka-tracing-aggregator/bin

COPY target/kafka-tracing-aggregator-*-bin.tar.gz /

RUN  echo "install kafka-tracing-aggregator" \
  && tar xzf /kafka-tracing-aggregator-*-bin.tar.gz \
  && rm /kafka-tracing-aggregator-*-bin.tar.gz \
  && ln -s /kafka-tracing-aggregator-* /kafka-tracing-aggregator \
  && echo "install JMX exporter for Java" \
  && apt-get update \
  && apt-get install wget -y \
  && wget -O /kafka-tracing-aggregator/jmx_prometheus_javaagent.jar https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/${PROMETHEUS_VERSION}/jmx_prometheus_javaagent-${PROMETHEUS_VERSION}.jar \
  && chmod 644 /kafka-tracing-aggregator/jmx_prometheus_javaagent.jar

WORKDIR /kafka-tracing-aggregator

EXPOSE 8080

ENTRYPOINT ["tracing-aggregator.sh"]
