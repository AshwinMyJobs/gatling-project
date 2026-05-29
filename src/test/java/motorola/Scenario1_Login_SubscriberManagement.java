package motorola;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Meaningful Name: Scenario1_Login_SubscriberManagement
 * Purpose: Full Journey - Auth, Baseline, and the 3-step Subscriber sequence.
 */
public class Scenario1_Login_SubscriberManagement extends Simulation {

    private FeederBuilder<String> userFeeder = csv("users.csv").circular();

    // 2. HTTP Base Options adjusted to match Chrome characteristics
    private HttpProtocolBuilder httpProtocol = http
            .baseUrl("https://wms-dev-xdmauto.msiidcitgcloud.com")
            .wsBaseUrl("wss://wms-dev-xdmauto.msiidcitgcloud.com")
            // 🌟 FIXED: Clean absolute subdomain mapping with no leading colons or slashes
            .header("Host", "wms-dev-xdmauto.msiidcitgcloud.com")
            .acceptHeader("application/json, text/plain, */*")
            .acceptLanguageHeader("en-US,en;q=0.9")
            .userAgentHeader("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .disableCaching()
            // 🌟 FIXED: Protect downstream loops from getting auto-mutated referers
            .disableAutoReferer()
            // --- CRITICAL CONNECTION FIXES FOR PREMATURE CLOSE ---
            .connectionHeader("keep-alive")
            .shareConnections()
            .maxConnectionsPerHost(6);


    // --- 1. LOGIN GROUP ---
// Update the Login Group to check for successful authentication
    private static ChainBuilder loginGroup = group("Step 01: Auth & Login").on(
                    exec(http("1. Initial Redirect")
                            .get("/cat/view/idmlogin")
                            // 👇 ADD THIS LINE to stop Gatling from following the 302
                            .disableFollowRedirect()
                            .check(status().is(302),
                                    header("Location").saveAs("keycloakUrl")))
                            .exec(http("2. Load Keycloak Page")
                                    // Now we manually go to the URL we just saved
                                    .get(session -> session.getString("keycloakUrl"))
                                    .check(status().is(200),
                                            regex("var loginAction = '([^']*)'").saveAs("rawLoginAction")))
                    .exec(session -> {
                        // Double check replacement of &amp;
                        String raw = session.getString("rawLoginAction");
                        return session.set("loginAction", raw.replace("&amp;", "&"));
                    })
                    .exec(http("3. Submit Credentials")
                            .post(session -> session.getString("loginAction"))
                            .formParam("username", "#{username}")
                            .formParam("password", "#{password}")
                            .formParam("login", "log in")
                            // CHECK: Ensure we get redirected back to the app or get a session cookie
                            .check(status().in(200, 302)))
    );

    // --- 2. BASELINE PAGE LOAD GROUP ---
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

    // --- 3. SUBSCRIBER MANAGEMENT (The 3-Call Sequence) ---
    private static ChainBuilder subscriberManagement = group("Step 03: Subscriber Management").on(
            // Call 1: Corp Hierarchy
            exec(http("10. API: Get Corp Hierarchy")
                    .post("/cat/rest/getCorpHierarchy")
                    .header("Content-Type", "application/json")
                    .body(StringBody("{}"))
                    .check(status().is(200)))
                    // Call 2: Pagination Info
                    .exec(http("11. API: Get Pagination Info")
                            .post("/cat/rest/getMasterList/getPaginationInfo")
                            .header("Content-Type", "application/json")
                            .body(StringBody("{\"rowsPerPage\":\"50\",\"hierarchy_id\":\"\",\"filterType\":\"0\"}"))
                            .check(status().is(200)))
                    // Call 3: Get All Agency Subs List (New from your cURL)
                    .exec(http("12. API: Get All Agency Subs List")
                            .post("/cat/rest/getAllAgencySubsList")
                            .header("Content-Type", "application/json")
                            .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com/cat/static/cobalt-ngcatui/index.html")
                            .body(StringBody("{\"hierarchy_id\":\"\",\"filterType\":0,\"offset\":\"0\",\"rowsPerPage\":50,\"clientTypeFilter\":[],\"OrgFilter\":[],\"searchString\":\"\"}"))
                            .check(status().is(200)))
    );

    // --- 4. LOGOUT GROUP ---
    private static ChainBuilder logoutGroup = group("Step 04: Session Cleanup").on(
            exec(http("13. Logout").get("/cat/view/logout").check(status().in(200, 302)))
                    .exec(flushCookieJar())
    );

    // --- SCENARIO DEFINITION ---
    // Add a pause to simulate real user behavior and prevent "Busy" errors
    private ScenarioBuilder scn = scenario("CAT E2E User Lifecycle - Subscriber Management")
            .feed(userFeeder)
            .exec(loginGroup)
            .pause(1) // Small gap to let session propagate
            .exec(baselinePageLoad)
            .pause(2)
            .exec(subscriberManagement)
            .exec(logoutGroup);

//    {
//        setUp(
//                // Ramps 10 users over 5 minutes (very gentle)
//                scn.injectOpen(rampUsers(5).during(Duration.ofSeconds(30)))
//        ).protocols(httpProtocol);
//    }

    // --- 5. SCENARIO PROFILE EXECUTION ---
    {
        setUp(
                scn.injectOpen(
                        // 1. Let the system stabilize for 5 seconds
                        nothingFor(5),
                        // 2. Inject an initial batch of 10 concurrent users instantly
                        atOnceUsers(10),
                        // 3. Smoothly ramp up the remaining 990 users over 15 minutes
                        rampUsers(990).during(Duration.ofMinutes(15))
                )
        ).protocols(httpProtocol);
    }

}
