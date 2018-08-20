package com.example;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.testkit.javadsl.TestKit;
import akka.util.Timeout;
import org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static akka.pattern.PatternsCS.ask;
import static org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner.newConfigs;
import static org.junit.Assert.assertEquals;

public class AppTest {

    private static ActorSystem system;
    private static Materializer materializer;
    private static TestKit testKit;
    private static ElasticsearchClusterRunner elasticsearchClusterRunner;

    @BeforeClass
    public static void setup() {
        system = ActorSystem.create("AppTest");
        materializer = ActorMaterializer.create(system);
        testKit = new TestKit(system);
        Kafka.startKafkaServer();

        // start elastic search
        elasticsearchClusterRunner = new ElasticsearchClusterRunner();
        elasticsearchClusterRunner.onBuild((number, settingsBuilder) -> {
            // put elasticsearch settings
            // settingsBuilder.put("destinationIndex.number_of_replicas", 0);
        }).build(newConfigs());
    }

    @AfterClass
    public static void teardown() throws IOException {
        system.terminate();
        Kafka.stopKafkaServer();
        elasticsearchClusterRunner.close();
        elasticsearchClusterRunner = null;
    }

    @Test
    public void endToEndTest() throws Exception {
        Kafka.createCustomTopic("my-kafka-topic", 10, 1);

        MonitoringStreamActor.Settings settings = new MonitoringStreamActor.Settings(
            "127.0.0.1:" + Kafka.getConfig().kafkaPort(),
            "127.0.0.1",
            9201
        );

        ActorRef streamActor =
            system.actorOf(MonitoringStreamActor.props(settings, materializer));

        Kafka.publishToKafka("my-kafka-topic", "message1");
        Kafka.publishToKafka("my-kafka-topic", "bad data");


        // here we cannot know if the element has reached ES yet,
        // we need to to do an assertion that will turn true within some interval

        final Client esClient = elasticsearchClusterRunner.client();

        // check ok messages
        testKit.awaitAssert(Duration.ofSeconds(10), () -> {
            try {
                SearchResponse result = esClient.search(new SearchRequest("normal_messages"))
                    .get(1, TimeUnit.SECONDS);
                assertEquals(1, result.getHits().totalHits);
                assertEquals(
                    "message1",
                    result.getHits().getHits()[0].getSourceAsMap().get("field"));

                return result;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        // check failed-to-parse messages
        testKit.awaitAssert(Duration.ofSeconds(10), () -> {
            try {
                SearchResponse result = esClient.search(new SearchRequest("failed_messages"))
                    .get(1, TimeUnit.SECONDS);
                assertEquals(1, result.getHits().totalHits);
                return result;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        // block on graceful stream completion
        testKit.watch(streamActor);
        ask(streamActor,
            MonitoringStreamActor.SHUTDOWN_GRACEFULLY,
            Timeout.create(Duration.ofSeconds(20)))
            .toCompletableFuture()
            .get(25, TimeUnit.SECONDS);
        // also, verify that the actor stopped when the stream did
        testKit.expectTerminated(streamActor);
    }
}
