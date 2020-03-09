FROM openjdk:11.0.3-slim

ENV PATH ${PATH}:/tracing-aggregator/bin

COPY target/tracing-aggregator-*-bin.tar.gz /

RUN  echo "install tracing-aggregator" \
  && tar xzf /tracing-aggregator-*-bin.tar.gz \
  && rm /tracing-aggregator-*-bin.tar.gz \
  && ln -s /tracing-aggregator-* /tracing-aggregator

WORKDIR /tracing-aggregator

ENTRYPOINT ["tracing-aggregator.sh"]
