package com.example;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.pattern.Backoff;
import akka.pattern.BackoffOnRestartSupervisor;
import akka.pattern.BackoffSupervisor;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import scala.concurrent.duration.FiniteDuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class App {

    public static void main(String[] args) {
        // Akka bootstrap
        final ActorSystem system = ActorSystem.create("example");
        final Materializer materializer = ActorMaterializer.create(system);

        // these would come from config, but put here to make the sample self contained
        MonitoringStreamActor.Settings settings = new MonitoringStreamActor.Settings(
            "127.0.0.1:9091",
            "127.0.0.1",
            9201
        );

        // use a special parent that will back off and then start the actor again
        // if it fails, except for that it transparently behavs as it was the
        // supervised actor itself, forwards any message sent to it to the child etc.
        ActorRef supervisor = system.actorOf(
            BackoffSupervisor.props(
                Backoff.onFailure(
                    MonitoringStreamActor.props(settings, materializer),
                    "monitor-stream",
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(2),
                    0
                ).withAutoReset(FiniteDuration.create(1, TimeUnit.MINUTES))
            ),
            "supervisor"
        );

        // now a web route could do
        /*
        import static akka.pattern.PatternsCS.ask;

        ask(streamActor, MonitoringStreamActor.SHUTDOWN_GRACEFULLY, Timeout.create(Duration.ofSeconds(15)))
            .whenComplete((done, failure) -> {
                // it either completed gracefully within the timeout, or it timed out
                // in any case, lets' terminate the actor system
                system.terminate();
            });
        */
    }

}
