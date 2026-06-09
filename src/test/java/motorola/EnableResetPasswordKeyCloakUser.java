package motorola;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * This simulation is used to mass-enable user accounts in Keycloak and reset their passwords using the Keycloak Admin API.
 * The users which are created using CreateUsersSimulation_OrgAdmin and CreateUsersSimulation_DeptAdmin
 * should be entered in the EnableResetPasswordKeyCloakUser.csv with super admin credentials in the begining
 * followed by the username, email, and new password for each user to be enabled and reset.
 */

public class EnableResetPasswordKeyCloakUser extends Simulation {

    // 1. Direct Backend Infrastructure Configuration
    private static final String BASE_URL = "http://52.172.33.197:8200";
    private static final String REALM = "catadminusers";

    // IMPORTANT: Ensure you paste a fresh, unexpired Access Token right before executing
    //This is from keycloak admin console -> Network tab -> copy the entire Bearer token value from your active browser session
    private static final String RAW_TOKEN = "eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICIwUXZ1V1NvcWh2bl9NN0ZKOHl6N01DZ2FTTEdiYkJEdUJhYWgxbzFtZXNZIn0.eyJleHAiOjE3ODQ1MzMzMDYsImlhdCI6MTc3OTM0OTMwNiwianRpIjoib25sdGFjOjQwYWY0MjUyLWMzYWMtZWUxNi1iZDNhLTQzNTc3M2U5Y2JmYiIsImlzcyI6Imh0dHA6Ly81Mi4xNzIuMzMuMTk3OjgyMDAvYXV0aC9yZWFsbXMvbWFzdGVyIiwidHlwIjoiQmVhcmVyIiwiYXpwIjoic2VjdXJpdHktYWRtaW4tY29uc29sZSIsInNpZCI6IjBjNTZmODA4LWRkZmItOWNiMi1kYzFjLTljZWNmODI4MmZiOCIsInNjb3BlIjoib3BlbmlkIGVtYWlsIHByb2ZpbGUifQ.WasGcJtTTL0kDGWe1uA9au0lfivKBJPMKXp_rT4WWpzndHOk02j2-Yert91dhB5Ffa5a_0vPvMBTZ5TAIz3K2e3ygPD-Qd02OA4xZIwZpFrN0JchIPT7bTdPA5VcFlpXT8Jn_q93MNntHoIjVE9mW9hSC6t34PfUStHlau97hblMpf15ap0E4qf5FJXOIqHtln6VswieV5ySJA7NCdfzmuEAL7-nMtULdwKzBpGBlxt5NixgkgcTQqwBoFGiLyeWUvccQON8RSKfRDhnPIEyu-SPbTJR94mhkoE4bpVXE5YW9itp2vUA6Z2fdZwc2Fg6N0ByEpbvFxQkuudRoJma1A";

    // 2. Base HTTP Protocol Configuration
    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json, text/plain, */*")
            .acceptLanguageHeader("en-US,en;q=0.9")
            .userAgentHeader("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
            .shareConnections();

    // 3. High-Performance CSV Data Feeder Configuration (Looking directly in src/test/resources/)
    private final FeederBuilder<String> csvFeeder = csv("EnableResetPasswordKeyCloakUser.csv").circular();

    // 4. Step 1: Query Keycloak to find the user profile and extract its dynamic tracking UUID
    private final ChainBuilder findUserUuid = exec(
            http("0a. Find Keycloak UUID")
                    .get("/auth/admin/realms/" + REALM + "/users")
                    .queryParam("username", "#{username}")
                    .queryParam("exact", "true")
                    .header("Authorization", "Bearer " + RAW_TOKEN)
                    .check(status().is(200))
                    // Will extract the ID if the user exists
                    .check(jsonPath("$[0].id").optional().saveAs("keycloakUserId"))
    ).exitHereIfFailed(); // Gracefully stops only the users that aren't found in Keycloak


    // 5. Step 2: Submit Profile Update to flag account state as Active & Enabled
    private final ChainBuilder enableUserAccount = exec(
            http("0b. Enable User Account")
                    .put("/auth/admin/realms/" + REALM + "/users/#{keycloakUserId}")
                    .header("Authorization", "Bearer " + RAW_TOKEN)
                    .header("Content-Type", "application/json")
                    .body(StringBody("{"
                            + "\"username\":\"#{username}\","
                            + "\"email\":\"#{email}\","
                            + "\"enabled\":true,"
                            + "\"emailVerified\":true,"
                            + "\"attributes\":{\"corpid\":[\"AshwinCorp\"]}"
                            + "}"))
                    .check(status().in(200, 204))
    );

    // 6. Step 3: Execute Administrative Password Reset using your CSV dynamic file password value
    private final ChainBuilder resetPassword = exec(
            http("0c. Reset User Password")
                    .put("/auth/admin/realms/" + REALM + "/users/#{keycloakUserId}/reset-password")
                    .header("Authorization", "Bearer " + RAW_TOKEN)
                    .header("Content-Type", "application/json")
                    .body(StringBody("{\n" +
                            "  \"type\": \"password\",\n" +
                            "  \"value\": \"#{password}\",\n" +
                            "  \"temporary\": false\n" +
                            "}"))
                    .check(status().is(204))
    );

    // 7. Complete Integrated Scenario Definition
    private final ScenarioBuilder scn = scenario("Keycloak Mass Activation & Password Reset Chain")
            .feed(csvFeeder)
            .exitBlockOnFail().on(
                    exec(findUserUuid)
                            .pause(1)
                            .exec(enableUserAccount)
                            .pause(1)
                            .exec(resetPassword)
            );

    // 8. Execution Profile Configuration (Modified for a single-user sanity check)
    {
        setUp(
                scn.injectOpen(atOnceUsers(9))
        ).protocols(httpProtocol);
    }
}





















































//package motorola;
//
//import static io.gatling.javaapi.core.CoreDsl.*;
//import static io.gatling.javaapi.http.HttpDsl.*;
//import io.gatling.javaapi.core.*;
//import io.gatling.javaapi.http.*;
//
///**
// * Meaningful Name: EnableKeyCloakUser
// * Purpose: Connects directly to the Keycloak Admin API to mass-discover
// * user UUIDs and toggle their activation status to enabled.
// */
//public class CreateAndEnableKeyCloakUser extends Simulation {
//
//    // 1. Feeder configured with basic text encoding to safely handle symbols inside passwords
//    private FeederBuilder<String> userFeeder = csv("users_enable.csv").queue();
//
//    // 2. Keycloak Admin API Protocol Configuration
//    private HttpProtocolBuilder kcAdminProtocol = http
//            .baseUrl("http://52.172.33.197:8200")
//            .acceptHeader("application/json, text/plain, */*")
//            .contentTypeHeader("application/json")
//            // PASTE THE ENTIRE ACTIVE BROWSER TOKEN FROM YOUR DEV TOOLS HERE
//            .authorizationHeader("Bearer eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICIwUXZ1V1NvcWh2bl9NN0ZKOHl6N01DZ2FTTEdiYkJEdUJhYWgxbzFtZXNZIn0.eyJleHAiOjE3ODQ0MzQ3NjQsImlhdCI6MTc3OTI1MDc2NCwianRpIjoib25sdGFjOjVkMmFjM2JhLTYyY2EtZWUxNS05NTRlLWI1YjlmMWZhODFhMiIsImlzcyI6Imh0dHA6Ly81Mi4xNzIuMzMuMTk3OjgyMDAvYXV0aC9yZWFsbXMvbWFzdGVyIiwidHlwIjoiQmVhcmVyIiwiYXpwIjoic2VjdXJpdHktYWRtaW4tY29uc29sZSIsInNpZCI6IjZmODEyZjY4LWJlMWYtZjA1MC0yYWM5LWY4OWI4ZDIwMWE1NSIsInNjb3BlIjoib3BlbmlkIGVtYWlsIHByb2ZpbGUifQ.FBYP369pbYgXNEbVBBQ6K-lXUK5sFi2QolL9q8vJeL9qptPjRJm3jYu6_k6ypTrEFLL_GTR3-sxQZgwCKkcQG_G9qGmuDXAgoj1vapVDkxKcicihWvnVw4h9LyH59uYxG0EPPmpzte8MEOu0FVaHs7y_MWMMAbaEVFmZMnKK8M4LmCLizbgKpQsFKuHefWl_Jh3JP40PqZz7v0YMXoKfDk4vrNuJhrXneV3_A5M3sRLE6KwSH0C4PV_BSo95t0YUnDF9jRHC6KblxoGYaulYWqaYJ-YBfR-hQB4W7Bm8GuzdtWwHOS4ML_WbLYlsXpAUnzrSKketFYUQ9gyaN7Rw0Q");
//
//    // 3. Automation Scenario Flow
//    private ScenarioBuilder scn = scenario("Keycloak Mass Activation Chain")
//            .feed(userFeeder)
//            .exitBlockOnFail().on(
//                    // Step A: Find the user's hidden tracking UUID in Keycloak
//                    exec(http("0a. Find Keycloak UUID")
//                            .get("/auth/admin/realms/catadminusers/users")
//                            .queryParam("username", "#{username}")
//                            .queryParam("exact", "true")
//                            .check(status().is(200))
//                            // Safely extracts the string matching 'id' from the list response
//                            .check(jsonPath("$[0].id").saveAs("keycloak_uuid"))
//                    )
//                            .pause(1)
//
//                            // Step B: Submit Call 1 (PUT) using the extracted UUID to flag as Enabled
//                            .exec(http("0b. Enable User Account")
//                                    .put(session -> "/auth/admin/realms/catadminusers/users/" + session.getString("keycloak_uuid"))
//                                    .body(StringBody("{"
//                                            + "\"username\":\"#{username}\","
//                                            + "\"email\":\"#{email}\","
//                                            + "\"enabled\":true,"
//                                            + "\"emailVerified\":true,"
//                                            + "\"attributes\":{\"corpid\":[\"AshwinCorp\"]}"
//                                            + "}"))
//                                    .check(status().in(200, 204))
//                            )
//            );
//
//    {
//        // Ramps 1,000 admin creations over 10 minutes (600 seconds)
//        // This maintains a safe, steady pace of ~1.6 creations per second
//        setUp(
//                scn.injectOpen(rampUsers(1000).during(300))
//        ).protocols(kcAdminProtocol);
//    }
//}
