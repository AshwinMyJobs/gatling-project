package motorola;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

/**
 * Meaningful Name: EnableKeyCloakUser
 * Purpose: Connects directly to the Keycloak Admin API to mass-discover
 * user UUIDs and toggle their activation status to enabled.
 */
public class CreateAndEnableKeyCloakUser extends Simulation {

    // 1. Feeder configured with basic text encoding to safely handle symbols inside passwords
    private FeederBuilder<String> userFeeder = csv("users_enable.csv").queue();

    // 2. Keycloak Admin API Protocol Configuration
    private HttpProtocolBuilder kcAdminProtocol = http
            .baseUrl("http://52.172.33.197:8200")
            .acceptHeader("application/json, text/plain, */*")
            .contentTypeHeader("application/json")
            // PASTE THE ENTIRE ACTIVE BROWSER TOKEN FROM YOUR DEV TOOLS HERE
            .authorizationHeader("Bearer eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICIwUXZ1V1NvcWh2bl9NN0ZKOHl6N01DZ2FTTEdiYkJEdUJhYWgxbzFtZXNZIn0.eyJleHAiOjE3ODQ0MzQ3NjQsImlhdCI6MTc3OTI1MDc2NCwianRpIjoib25sdGFjOjVkMmFjM2JhLTYyY2EtZWUxNS05NTRlLWI1YjlmMWZhODFhMiIsImlzcyI6Imh0dHA6Ly81Mi4xNzIuMzMuMTk3OjgyMDAvYXV0aC9yZWFsbXMvbWFzdGVyIiwidHlwIjoiQmVhcmVyIiwiYXpwIjoic2VjdXJpdHktYWRtaW4tY29uc29sZSIsInNpZCI6IjZmODEyZjY4LWJlMWYtZjA1MC0yYWM5LWY4OWI4ZDIwMWE1NSIsInNjb3BlIjoib3BlbmlkIGVtYWlsIHByb2ZpbGUifQ.FBYP369pbYgXNEbVBBQ6K-lXUK5sFi2QolL9q8vJeL9qptPjRJm3jYu6_k6ypTrEFLL_GTR3-sxQZgwCKkcQG_G9qGmuDXAgoj1vapVDkxKcicihWvnVw4h9LyH59uYxG0EPPmpzte8MEOu0FVaHs7y_MWMMAbaEVFmZMnKK8M4LmCLizbgKpQsFKuHefWl_Jh3JP40PqZz7v0YMXoKfDk4vrNuJhrXneV3_A5M3sRLE6KwSH0C4PV_BSo95t0YUnDF9jRHC6KblxoGYaulYWqaYJ-YBfR-hQB4W7Bm8GuzdtWwHOS4ML_WbLYlsXpAUnzrSKketFYUQ9gyaN7Rw0Q");

    // 3. Automation Scenario Flow
    private ScenarioBuilder scn = scenario("Keycloak Mass Activation Chain")
            .feed(userFeeder)
            .exitBlockOnFail().on(
                    // Step A: Find the user's hidden tracking UUID in Keycloak
                    exec(http("0a. Find Keycloak UUID")
                            .get("/auth/admin/realms/catadminusers/users")
                            .queryParam("username", "#{username}")
                            .queryParam("exact", "true")
                            .check(status().is(200))
                            // Safely extracts the string matching 'id' from the list response
                            .check(jsonPath("$[0].id").saveAs("keycloak_uuid"))
                    )
                            .pause(1)

                            // Step B: Submit Call 1 (PUT) using the extracted UUID to flag as Enabled
                            .exec(http("0b. Enable User Account")
                                    .put(session -> "/auth/admin/realms/catadminusers/users/" + session.getString("keycloak_uuid"))
                                    .body(StringBody("{"
                                            + "\"username\":\"#{username}\","
                                            + "\"email\":\"#{email}\","
                                            + "\"enabled\":true,"
                                            + "\"emailVerified\":true,"
                                            + "\"attributes\":{\"corpid\":[\"AshwinCorp\"]}"
                                            + "}"))
                                    .check(status().in(200, 204))
                            )
            );

    {
        // Ramps 1,000 admin creations over 10 minutes (600 seconds)
        // This maintains a safe, steady pace of ~1.6 creations per second
        setUp(
                scn.injectOpen(rampUsers(1000).during(300))
        ).protocols(kcAdminProtocol);
    }
}
