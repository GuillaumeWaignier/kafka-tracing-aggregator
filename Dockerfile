FROM openjdk:11.0.3-slim


ARG PROMETHEUS_VERSION=0.11.0

ENV java_option -javaagent:/tracing-aggregator/jmx_prometheus_javaagent.jar=8080:/tracing-aggregator/config/prometheus-exporter.yml
ENV PATH ${PATH}:/tracing-aggregator/bin

COPY target/tracing-aggregator-*-bin.tar.gz /

RUN  echo "install tracing-aggregator" \
  && tar xzf /tracing-aggregator-*-bin.tar.gz \
  && rm /tracing-aggregator-*-bin.tar.gz \
  && ln -s /tracing-aggregator-* /tracing-aggregator \
  && echo "install JMX exporter for Java" \
  && apt-get update \
  && apt-get install wget -y \
  && wget -O /tracing-aggregator/jmx_prometheus_javaagent.jar https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/${PROMETHEUS_VERSION}/jmx_prometheus_javaagent-${PROMETHEUS_VERSION}.jar \
  && chmod 644 /tracing-aggregator/jmx_prometheus_javaagent.jar

WORKDIR /tracing-aggregator

EXPOSE 8080

ENTRYPOINT ["tracing-aggregator.sh"]
