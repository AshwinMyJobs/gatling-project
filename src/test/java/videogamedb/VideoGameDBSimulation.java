package videogamedb;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class VideoGameDBSimulation extends Simulation{

    HttpProtocolBuilder httpProtocolBuilder = http
            .baseUrl("https://videogamedb.uk:443/api")
            .acceptHeader("application/json");

    ScenarioBuilder scn = scenario("Video Game DB Stress Test")
            .exec(http("Get All Games")
                    .get("/videogame"));

    {
        setUp(
                scn.injectOpen(
                        // This will gradually add 100 users over a 30-second window
                        rampUsers(500).during(60)
                )
        ).protocols(httpProtocolBuilder);
    }
}