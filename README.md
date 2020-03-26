# kafka-tracing-aggregator

[![Build status](https://travis-ci.org/GuillaumeWaignier/kafka-tracing-aggregator.svg?branch=master)](https://travis-ci.org/GuillaumeWaignier/kafka-tracing-aggregator)

Aggregate Kafka traces produced by the [trace interceptor](https://github.com/GuillaumeWaignier/kafka-tracing-interceptors) in order to:
* Enrich each trace with topic, partition, offset and correlationId 
* Compute the latency between message produced and consumed
* Compute the over consumption

All aggregated traces are kafka messages sent to the topic **\_aggregatedTrace**.

## Usage (Command line)

```bash
./bin/tracing-aggregator.sh ./config/config.properties
```

The kafka configuration file correspond to the standard kafka [kafka stream config](https://kafka.apache.org/documentation/#streamsconfigs).


_Exemple of configuration file_

```properties
bootstrap.servers=localhost:9092
application.id=_aggregator-trace-stream
state.dir=/tmp
```

_Export metric with prometheus_

[JMX exporter](https://github.com/prometheus/jmx_exporter) can be used to export metrics for Prometheus.

```bash
export java_option=-javaagent:/jmx_prometheus_javaagent-0.11.0.jar=8080:/config/prometheus-exporter.yml
./bin/tracing-aggregator.sh ./config/config.properties
```

## Usage (Docker)

```bash
docker run -e KAFKATRACE_BOOTSTRAP_SERVERS=kafkaip:9092 -p 8080:8080 ianitrix/kafka-tracing-aggregator:latest
```

_Environment variables_

All kafka configuration is done with environment variables prefixed with **KAFKATRACE_**

All dot is replaced by underscore and the variable name must be in upper case.


# Tracing

All traces are kafka messages sent to the topic **\_aggregatedTrace**.

## Send

key:
````yaml
{ 
  "topic": "test",
  "partition": 0,
  "offset": 0
}
````

value:

````yaml
{
  "date": "2020-03-25T15:41:01.365287Z",
  "type": "SEND",
  "topic": "test",
  "partition": 0,
  "offset": 0
  "correlationId": "af8074bc-a042-46ef-8064-203fa26cd9b3",
  "clientId": "myProducer"
}
````

## Ack

key:
````yaml
{
  "topic": "test",
  "partition": 0,
  "offset": 0
}
````

value:
````yaml
{
  "date": "2020-03-25T15:41:01.966082Z",
  "type": "ACK",
  "topic": "test",
  "partition": 0,
  "offset": 0,
  "correlationId": "af8074bc-a042-46ef-8064-203fa26cd9b3",
  "clientId": "myProducer",
  "durationMs": 600
}
````

## Consume

key:
````yaml
{
  "topic": "test",
  "partition": 0,
  "offset": 0
}
````

value:
````yaml
{
  "date": "2020-03-25T15:41:37.844039Z",
  "type": "CONSUME",
  "topic": "test",
  "partition": 0,
  "offset": 0,
  "correlationId": "af8074bc-a042-46ef-8064-203fa26cd9b3",
  "clientId": "myConsumer",
  "groupId": "myGroup",
  "durationMs": 36478
}
````

## Commit

key:
````yaml
{
  "topic": "test",
  "partition": 0,
  "offset": 0
}
````

value:
````yaml
{
  "date": "2020-03-25T15:41:37.851339Z",
  "type": "COMMIT",
  "topic": "test",
  "partition": 0,
  "offset": 0,
  "correlationId": "af8074bc-a042-46ef-8064-203fa26cd9b3",
  "clientId": "myConsumer",
  "groupId": "myGroup",
  "durationMs": 7
}
````


# Dashboard

TODO

# Test

Docker-compose files provide a full environment with kafka/Elasticsearch/kibana and a simple producer/consumer.

There are 2 docker-composes file:

* Kafka environment and raw trace

First, start the docker-compose of the Test section explain in [trace interceptor](https://github.com/GuillaumeWaignier/kafka-tracing-interceptors) Git Project.

* Elasticsearch environment and aggregated trace

````shell
cd src/test/resources
docker-compose up -d
````

This docker-compose file start kafka connect, Elasticsearch and Kibana.
Then, it will start a connector that indexes aggregated trace inside Elasticsearch.
It will also load the Elasticsearch dashboard 

Current bug : if there is no dashboard, do *docker-compose up -d* a second time when kibana is started.


Then open [http://localhost:5601/](http://localhost:5601/) or [http://localhost:8080/](http://localhost:8080/)

