package com.example;


import akka.Done;
import akka.kafka.javadsl.Consumer;
import akka.testkit.javadsl.TestKit;
import org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner.newConfigs;
import static org.junit.Assert.assertEquals;

public class AppTest {

    private static ElasticsearchClusterRunner elasticsearchClusterRunner;

    @BeforeClass
    public static void setup() {
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
        Kafka.stopKafkaServer();
        elasticsearchClusterRunner.close();
        elasticsearchClusterRunner = null;
    }

    @Test
    public void endToEndTest() throws Exception {
        Kafka.createCustomTopic("my-kafka-topic", 10, 1);

        final App app = new App();
        app.kafkaBootstrapServers = "127.0.0.1:" + Kafka.getConfig().kafkaPort();
        app.elasticSearchServer = "127.0.0.1";
        app.elasticSearchPort = 9201; // the ES cluster runner starts enumerating ports from 9201

        Consumer.DrainingControl<Done> streamStopped = app.run();
        Kafka.publishToKafka("my-kafka-topic", "message1");
        Kafka.publishToKafka("my-kafka-topic", "bad data");


        // here we cannot know if the element has reached ES yet,
        // we need to to do an assertion that will turn true within some interval
        TestKit testKit = new TestKit(app.system);
        final Client esClient = elasticsearchClusterRunner.client();
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

        // block on stream completion
        streamStopped.drainAndShutdown(ForkJoinPool.commonPool())
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

    }
}
