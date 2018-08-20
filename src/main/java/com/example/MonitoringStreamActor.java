package com.example;

import akka.Done;
import akka.NotUsed;
import akka.actor.AbstractLoggingActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.Status;
import akka.kafka.ConsumerMessage;
import akka.kafka.ConsumerSettings;
import akka.kafka.Subscriptions;
import akka.kafka.javadsl.Consumer;
import akka.stream.Materializer;
import akka.stream.OverflowStrategy;
import akka.stream.alpakka.elasticsearch.ElasticsearchSinkSettings;
import akka.stream.alpakka.elasticsearch.IncomingMessage;
import akka.stream.alpakka.elasticsearch.IncomingMessageResult;
import akka.stream.alpakka.elasticsearch.javadsl.ElasticsearchFlow;
import akka.stream.javadsl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.elasticsearch.client.RestClient;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import static akka.pattern.PatternsCS.pipe;
import static com.example.Parsing.ParsedRecord;
import static com.example.Parsing.parseKafkaMessage;

public class MonitoringStreamActor extends AbstractLoggingActor {

  public static class Settings {
    final String kafkaBootstrapServers;
    final String elasticSearchServer;
    final int elasticSearchPort;
    public Settings(String kafkaBootstrapServers, String elasticSearchServer, int elasticSearchPort) {
      this.kafkaBootstrapServers = kafkaBootstrapServers;
      this.elasticSearchServer = elasticSearchServer;
      this.elasticSearchPort = elasticSearchPort;
    }
  }

  // message protocol
  public static Object SHUTDOWN_GRACEFULLY = new Object();


  public static Props props(Settings settings, Materializer materializer) {
    return Props.create(MonitoringStreamActor.class, () -> new MonitoringStreamActor(settings, materializer));
  }


  private final RestClient esClient;
  private final Consumer.DrainingControl<Done> drainingControl;
  private boolean gracefulShutdownInProgress = false;

  public MonitoringStreamActor(Settings settings, Materializer materializer) {
    // kafka consumer setup
    final ConsumerSettings<byte[], byte[]> consumerSettings =
        // option: we could choose to instead do parsing/deserialization already here, with the Kafka infrastructure
        ConsumerSettings.create(getContext().getSystem(), new ByteArrayDeserializer(), new ByteArrayDeserializer())
            .withBootstrapServers(settings.kafkaBootstrapServers)
            .withGroupId("my-consumer-group")
            // I think this may be a bit dangerous because TTL of offset, if no write within N hours it will be lost
            .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    final Source<ConsumerMessage.CommittableMessage<byte[], byte[]>, Consumer.Control> kafkaSource =
        Consumer.committableSource(consumerSettings, Subscriptions.topics("my-kafka-topic"));

    // elastic search client setup
    esClient = RestClient.builder(new HttpHost(
        settings.elasticSearchServer,
        settings.elasticSearchPort)).build();

    final Flow<IncomingMessage<ParsedRecord, ConsumerMessage.CommittableMessage<byte[], byte[]>>, List<IncomingMessageResult<ParsedRecord, ConsumerMessage.CommittableMessage<byte[], byte[]>>>, NotUsed> writeToElasticSearch =
        ElasticsearchFlow.createWithPassThrough(
            "not-actually-used-messages-decides",
            "my-type-name",
            ElasticsearchSinkSettings.Default()
                // if not disabled, this could re-order messages and break committing to kafka
                .withRetryPartialFailure(false)
                // these could also be configured in application.conf
                .withBufferSize(10)
                .withMaxRetry(100)
                .withRetryInterval(5000),
            esClient,
            // uses Jackson for serialization
            new ObjectMapper());

    // construct stream
    final RunnableGraph<Consumer.DrainingControl<Done>> kafkaToEsGraph = kafkaSource
        // make sure we eagerly fetch, up to 30 records at all times - this is an optimization
        // but it would be good to know if it really helps anything
        .buffer(30, OverflowStrategy.backpressure())
        // parse the kafka message into our own data structure - but keep the original kafka message
        // so that we can commit it when done
        .map(kafkaMessage -> {
          // decision: how to deal with parse errors here - fail stream and require manual solution, throw away message?
          // decision: this is also where you have shared mutable state - we need to deal with that
          ParsedRecord parsed = parseKafkaMessage(kafkaMessage.record().value());

          // incoming message is specific to the es-connector

          return IncomingMessage.create(parsed, kafkaMessage)
              // allows writing to different indexes in the same batch
              .withIndexName(parsed.destinationIndex());
        })
        // the elastic search flow does batching and bulk inserts, and retries
        .via(writeToElasticSearch)
        .mapAsync(1, writeResults -> {
          // figure out if any write in the batch failed
          List<IncomingMessageResult<ParsedRecord, ConsumerMessage.CommittableMessage<byte[], byte[]>>> failures =
              writeResults.stream()
                  .filter(result -> !result.success())
                  .collect(Collectors.toList());

          if (failures.isEmpty()) {
            // everything was ok, let's do a batched commit
            List<ConsumerMessage.CommittableOffset> offsets = writeResults.stream()
                .map(result -> result.passThrough().committableOffset())
                .collect(Collectors.toList());

            ConsumerMessage.CommittableOffsetBatch committableOffsetBatch = ConsumerMessage.createCommittableOffsetBatch(offsets);
            return committableOffsetBatch.commitJavadsl();
          } else {
            // decision: how to deal with ES-insert errors here - fail stream?
            throw new RuntimeException("There was failed inserts: " + failures);
          }
        })
        // this is a technicality, a more natural way of thinking about it would be .foreachAsync(1, elem -> CS())
        // which is worked on in Akka issue #25152, will likely end up in 2.5.15
        .toMat(Sink.ignore(), Keep.both())
        .mapMaterializedValue(Consumer::createDrainingControl);

    drainingControl = kafkaToEsGraph.run(materializer);
    log().info("Stream started");

    // make sure we get a message about the stream stopping or failing
    // for success the Done is sent directly, for failure we get an
    // akka.actor.Status.Failure(exception)
    pipe(drainingControl.isShutdown(), getContext().dispatcher())
        .pipeTo(getSelf(), ActorRef.noSender());
  }

  @Override
  public Receive createReceive() {
    return receiveBuilder()
        .matchEquals(SHUTDOWN_GRACEFULLY, this::onShutdownGracefully)
        .matchEquals(Done.done(), this::onStreamDone)
        .match(Status.Failure.class, this::onStreamFailure)
        .build();
  }

  private void onShutdownGracefully(Object notUsed) {
    log().info("Graceful stream shutdown requested");
    gracefulShutdownInProgress = true;
    CompletionStage<Done> shutdownComplete =
        drainingControl.drainAndShutdown(getContext().getSystem().dispatcher());
    // let the graceful shutdown requestor know when done (or failed)
    pipe(shutdownComplete, getContext().dispatcher())
        .pipeTo(getSender(), getSelf());
  }

  private void onStreamDone(Done notUsed) {
    if (gracefulShutdownInProgress) {
      log().info("Graceful stream shutdown completed");
      getContext().stop(getSelf());
    } else {
      // if we didn't ask it to stop, it is a failure
      throw new RuntimeException("Stream stopped unexpectedly");
    }
  }

  private void onStreamFailure(Status.Failure failure) {
    log().error("Stream failed", failure.cause());
    // fail the actor, and let supervision deal with recovering
    throw new RuntimeException("Stream failed", failure.cause());
  }


  @Override
  public void postStop() throws Exception {
    if (!drainingControl.isShutdown().toCompletableFuture().isDone()) {
      log().warning("Stream actor stopped while stream is still running, terminating stream");
      // to a more drastic shutdown right away if the stream is still running
      drainingControl.shutdown();
    }
    esClient.close();
  }
}
