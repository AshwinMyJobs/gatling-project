package motorola;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import java.time.Duration;

/**
 * Meaningful Name: CATUserLifecycleSimulation
 * Purpose: Tests the full end-to-end journey of a CAT user,
 * including Auth, Persistent Notification Sockets, Dashboard Bootstrapping, and Logout.
 */
public class CATUserLifecycleSimulation extends Simulation {

    private FeederBuilder<String> userFeeder = csv("users2.csv").circular();

    private HttpProtocolBuilder httpProtocol = http
            .baseUrl("https://wms-dev-xdmauto.msiidcitgcloud.com")
            .wsBaseUrl("wss://wms-dev-xdmauto.msiidcitgcloud.com")
            .acceptHeader("application/json, text/plain, */*")
            .acceptLanguageHeader("en-US,en;q=0.9")
            .userAgentHeader("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .disableCaching()
            // --- CRITICAL CONNECTION FIXES FOR PREMATURE CLOSE ---
            .connectionHeader("keep-alive")    // Nicely asks the load balancer to keep the channel open organically
            .maxConnectionsPerHost(6);

//    // --- 1. LOGIN GROUP ---
//    private static ChainBuilder loginGroup = group("Step 01: Auth & Login").on(
//            exec(http("1. Initial Redirect")
//                    .get("/cat/view/idmlogin")
//                    .disableFollowRedirect()
//                    .check(status().is(302), header("Location").saveAs("keycloakUrl")))
//                    .exec(http("2. Load Keycloak Page")
//                            .get(session -> session.getString("keycloakUrl"))
//                            .check(status().is(200), regex("var loginAction = '([^']*)'").saveAs("rawLoginAction")))
//                    .exec(session -> {
//                        String raw = session.getString("rawLoginAction");
//                        return session.set("loginAction", raw != null ? raw.replace("&amp;", "&") : "");
//                    })
//                    .exec(http("3. Submit Credentials")
//                            .post(session -> session.getString("loginAction"))
//                            .formParam("username", "#{username}")
//                            .formParam("password", "#{password}")
//                            .formParam("login", "log in")
//                            .check(status().is(200)))
//    );

    // --- 1. LOGIN GROUP ---
    private static ChainBuilder loginGroup = group("Step 01: Auth & Login").on(
            // Request 1: Must keep disableFollowRedirect because we need to catch the "Location" header!
            exec(flushCookieJar()).
            exec(http("1. Initial Redirect")
                    .get("/cat/view/idmlogin")
                    .disableFollowRedirect()
                    .check(status().is(302), header("Location").saveAs("keycloakUrl")))

                    // Request 2: Loads the login page to extract the dynamic action form URL
                    .exec(http("2. Load Keycloak Page")
                            .get(session -> session.getString("keycloakUrl"))
                            .check(status().is(200), regex("var loginAction = '([^']*)'").saveAs("rawLoginAction")))

                    // Data transformer: Fixes HTML encoded characters inside the extracted URL
                    .exec(session -> {
                        String raw = session.getString("rawLoginAction");
                        return session.set("loginAction", raw != null ? raw.replace("&amp;", "&") : "");
                    })

                    // Request 3: Submit Credentials
                    .exec(http("3. Submit Credentials")
                            .post(session -> session.getString("loginAction"))
                            .formParam("username", "#{username}")
                            .formParam("password", "#{password}")
                            .formParam("login", "log in")
                            // CHANGE HERE: Tell Request 3 to expect the 302 redirect back to the app instead of letting it follow automatically
                            .disableFollowRedirect()
                            .check(status().is(302), header("Location").saveAs("callbackUrl")))

                    // Request 3b: Natively execute the callback to trade the code for cookies safely
                    .exec(http("3b. Keycloak Callback Handshake")
                            .get(session -> session.getString("callbackUrl"))
                            .check(status().is(200)))
    );

    // --- 1b. WEBSOCKET NOTIFIER PERSISTENCE GROUP ---
    private static ChainBuilder connectNotificationSocket = group("Step 01b: Connect Notification Socket").on(
            // Generate standard dynamic SockJS routing parameters matching your Firefox trace
            exec(session -> {
                int serverId = java.util.concurrent.ThreadLocalRandom.current().nextInt(100, 1000);
                String sessionId = java.util.UUID.randomUUID().toString().replaceAll("-", "").substring(0, 8);
                return session.set("wsServerId", serverId).set("wsSessionId", sessionId);
            })
                    // Open the persistent secure WebSocket tunnel (inherits auth state from cookies seamlessly)
                    .exec(ws("Connect Persistent Notification Socket")
                            .connect("/cat/notifier/#{wsServerId}/#{wsSessionId}/websocket")
                            .await(Duration.ofSeconds(5)).on(
                                    ws.checkTextMessage("Verify Handshake Connection")
                                            .matching(regex(".*")) // Validates that the channel establishes cleanly
                            ))
    );

    // --- 2. BASELINE PAGE LOAD GROUP (Data Bootstrapping) ---
    private static ChainBuilder baselinePageLoad = group("Step 02: Initial Page Load Baseline").on(
            exec(http("4. API: Get Global Data").post("/cat/rest/getGlobalData").check(status().is(200)))
                    .exec(http("5. API: Refresh Token").post("/cat/view/refreshToken").body(StringBody("{}")).check(status().is(200)))
                    .exec(http("6. API: Get Users Permissions").post("/cat/rest/getUsersPermissions").header("Content-Type", "application/json").body(StringBody("{\"agencyInfo\":{\"corpName\":\"AshwinCorp\"},\"userIdList\":[\"#{username}\"]}")).check(status().is(200)))
                    .exec(http("7. API: Sync Master List Info").post("/cat/rest/syncMasterListInfo").check(status().is(200)))
                    .exec(http("8. API: Get All Async Events").get("/cat/rest/getAllAsyncEvents").check(status().is(200)))
                    .repeat(4, "i").on(
                            exec(http("9. API: Get Subscriber Stats").get("/cat/rest/getSubscriberStats").check(status().is(200)))
                    )
    );

//    // --- 3. LOGOUT GROUP ---
//    private static ChainBuilder logoutGroup = group("Step 03: Session Cleanup").on(
//            // Explicitly terminate the active background stream pool before throwing out cookies
//            exec(ws("Close Active Notification Socket").close())
//                    .exec(http("10. Logout").get("/cat/view/logout").check(status().in(200, 302)))
//                    .exec(flushCookieJar())
//    );
// --- 3. LOGOUT GROUP ---
private static ChainBuilder logoutGroup = group("Step 03: Session Cleanup").on(
        // EXPLICITLY REMOVED WEBSOCKET CLOSE ORDER HERE
        exec(http("10. Logout").get("/cat/view/logout").check(status().in(200, 302)))
                .exec(flushCookieJar())
);


    // --- SCENARIO DEFINITION ---
    private ScenarioBuilder scn = scenario("CAT E2E User Lifecycle")
            .feed(userFeeder)
            .exitBlockOnFail().on( // Everything inside here must succeed or the user stops
                    exec(loginGroup)               // Executes Step 01: Auth & Login
                            .pause(3, 5)
                            //.exec(connectNotificationSocket) // Executes Step 01b: Socket Connection
                            .pause(1, 3)
                            .exec(baselinePageLoad)          // Executes Step 02: Initial Page Load Baseline
                            .pause(5)
            )
            // This runs regardless or after the block finishes
            .exec(logoutGroup);                  // Executes Step 03: Session Cleanup

//    {
//        // Reverted to 1-user sanity check for clean development
//        setUp(
//                scn.injectOpen(atOnceUsers(1))
//        ).protocols(httpProtocol);
//    }

//    {
//        // Remember to change to atOnceUsers(1) for your single user validation sweeps!
//        setUp(
//                scn.injectOpen(rampUsers(1000).during(Duration.ofSeconds(300)))
//        ).protocols(httpProtocol);
//    }

    // --- 1-HOUR CONTINUOUS WORKLOAD CONFIGURATION ---
    {
        setUp(
                scn.injectOpen(
                        // 1. Warm-up: Smoothly ramp up from 0 to 5 login sessions per second over 5 minutes
                        rampUsersPerSec(0).to(5).during(Duration.ofMinutes(5)),

                        // 2. Peak Time: Maintain a rock-solid 5 users logging in every second for 1 hour
                        constantUsersPerSec(5).during(Duration.ofHours(1))
                )
        )
                .protocols(httpProtocol)
                // Safety gate to cleanly shut down all long-running iterations after 1 hour and 10 minutes
                .maxDuration(Duration.ofHours(1).plusMinutes(10));
    }
}




















































//package motorola;
//
//import static io.gatling.javaapi.core.CoreDsl.*;
//import static io.gatling.javaapi.http.HttpDsl.*;
//import io.gatling.javaapi.core.*;
//import io.gatling.javaapi.http.*;
//import java.time.Duration;
//
///**
// * Meaningful Name: CATUserLifecycleSimulation
// * Purpose: Tests the full end-to-end journey of a CAT user,
// * including Auth, Dashboard Bootstrapping, and Logout.
// */
//public class CATUserLifecycleSimulation extends Simulation {
//
//    private FeederBuilder<String> userFeeder = csv("users.csv").circular();
//
//    private HttpProtocolBuilder httpProtocol = http
//            .baseUrl("https://wms-dev-xdmauto.msiidcitgcloud.com")
//            .acceptHeader("application/json, text/plain, */*")
//            .acceptLanguageHeader("en-US,en;q=0.9")
//            .userAgentHeader("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
//            .disableCaching()
//            .shareConnections(); // Keep the connection alive
//
//    // --- 1. LOGIN GROUP ---
//    private static ChainBuilder loginGroup = group("Step 01: Auth & Login").on(
//            exec(http("1. Initial Redirect")
//                    .get("/cat/view/idmlogin")
//                    .disableFollowRedirect()
//                    .check(status().is(302), header("Location").saveAs("keycloakUrl")))
//                    .exec(http("2. Load Keycloak Page")
//                            .get(session -> session.getString("keycloakUrl"))
//                            .check(status().is(200), regex("var loginAction = '([^']*)'").saveAs("rawLoginAction")))
//                    .exec(session -> {
//                        String raw = session.getString("rawLoginAction");
//                        return session.set("loginAction", raw != null ? raw.replace("&amp;", "&") : "");
//                    })
//                    .exec(http("3. Submit Credentials")
//                            .post(session -> session.getString("loginAction"))
//                            .formParam("username", "#{username}")
//                            .formParam("password", "#{password}")
//                            .formParam("login", "log in")
//                            .check(status().is(200)))
//    );
//
//    // --- 2. BASELINE PAGE LOAD GROUP (Data Bootstrapping) ---
//    private static ChainBuilder baselinePageLoad = group("Step 02: Initial Page Load Baseline").on(
//            exec(http("4. API: Get Global Data").post("/cat/rest/getGlobalData").check(status().is(200)))
//                    .exec(http("5. API: Refresh Token").post("/cat/view/refreshToken").body(StringBody("{}")).check(status().is(200)))
//                    .exec(http("6. API: Get Users Permissions").post("/cat/rest/getUsersPermissions").header("Content-Type", "application/json").body(StringBody("{\"agencyInfo\":{\"corpName\":\"AshwinCorp\"},\"userIdList\":[\"#{username}\"]}")).check(status().is(200)))
//                    .exec(http("7. API: Sync Master List Info").post("/cat/rest/syncMasterListInfo").check(status().is(200)))
//                    .exec(http("8. API: Get All Async Events").get("/cat/rest/getAllAsyncEvents").check(status().is(200)))
//                    .repeat(4, "i").on(
//                            exec(http("9. API: Get Subscriber Stats").get("/cat/rest/getSubscriberStats").check(status().is(200)))
//                    )
//    );
//
//    // --- 3. LOGOUT GROUP ---
//    private static ChainBuilder logoutGroup = group("Step 03: Session Cleanup").on(
//            exec(http("10. Logout").get("/cat/view/logout").check(status().in(200, 302)))
//                    .exec(flushCookieJar())
//    );
//
//    // --- SCENARIO DEFINITION ---
//    private ScenarioBuilder scn = scenario("CAT E2E User Lifecycle")
//            .feed(userFeeder)
//            .exec(loginGroup)
//            .pause(1, 3)
//            .exec(baselinePageLoad)
//            .pause(1)    // Simulate user "active" on dashboard
//            .exec(logoutGroup)
//            .pause(1);
//
//    {
//        // 1000 users over 5 mins (3.33 TPS) as per business expectation
//        setUp(
//                scn.injectOpen(rampUsers(2).during(Duration.ofSeconds(3)))
//        ).protocols(httpProtocol);
//    }
//
////    //This set up is to repeate the same 100 users for 30 mins so that selenium tests can be run in parallel
////    // to validate the performance results and also to check for any
////    // "Busy" errors in the logs which are indicative of system slowness under load.
////    {
////        setUp(
////                scn.injectClosed(
////                        // 1. Ramp seats from 0 to 100
////                        rampConcurrentUsers(0).to(100).during(Duration.ofMinutes(5)),
////                        // 2. Keep all 100 seats full for 25 more minutes
////                        constantConcurrentUsers(100).during(Duration.ofMinutes(25))
////                )
////        ).protocols(httpProtocol);
////    }
//}
