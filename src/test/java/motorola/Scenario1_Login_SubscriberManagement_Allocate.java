package motorola;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.util.UUID;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Meaningful Name: Scenario1_Login_SubscriberManagement_Allocate
 * Purpose: Advanced End-to-End Test for user authentication, data bootstrapping,
 *          and dynamic Subscriber Allocation, running continuously for 1 hour.
 * This simulation will allocate subscribers and then revoke them.
 * Feeder user_subscriber_mapping.csv contains the following
 * username,password,hierarchy_id,mdn contains
 * (here username/password can be of superadmin or any admin, hierarchy_id is the organization to which the subscriber
 *  belongs and mdn is the subscriber phone number)
 */
public class Scenario1_Login_SubscriberManagement_Allocate extends Simulation {

    // 1. Endless loop data feeder for safety across the 1-hour test duration
    private final FeederBuilder<String> userFeeder = csv("user_subscriber_mapping.csv").circular();

    // 2. HTTP Base Options adjusted to match Chrome 148 characteristics
    private HttpProtocolBuilder httpProtocol = http
            .baseUrl("https://wms-dev-xdmauto.msiidcitgcloud.com")
            .wsBaseUrl("wss://wms-dev-xdmauto.msiidcitgcloud.com")
            // 🌟 FIXED: Use the standard generic header mapping instead of the incorrect method
            .header("Host", "wms-dev-xdmauto.msiidcitgcloud.com")
            .acceptHeader("application/json, text/plain, */*")
            .acceptLanguageHeader("en-US,en;q=0.9")
            .userAgentHeader("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .disableCaching()
            // --- CRITICAL CONNECTION FIXES FOR PREMATURE CLOSE ---
            .connectionHeader("keep-alive")
            .shareConnections()
            .maxConnectionsPerHost(6);

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




    // --- 2. BASELINE PAGE LOAD GROUP ---
    private static final ChainBuilder baselinePageLoad = group("Step 02: Initial Page Load Baseline").on(
            exec(http("4. API: Get Global Data").post("/cat/rest/getGlobalData").check(status().is(200)))
                    .exec(http("5. API: Refresh Token").post("/cat/view/refreshToken").body(StringBody("{}")).check(status().is(200)))
                    .exec(http("6. API: Get Users Permissions").post("/cat/rest/getUsersPermissions").header("Content-Type", "application/json").body(StringBody("{\"agencyInfo\":{\"corpName\":\"AshwinCorp\"},\"userIdList\":[\"#{username}\"]}")).check(status().is(200)))
                    .exec(http("7. API: Sync Master List Info").post("/cat/rest/syncMasterListInfo").check(status().is(200)))
                    .exec(http("8. API: Get All Async Events").get("/cat/rest/getAllAsyncEvents").check(status().is(200)))
                    .repeat(4, "i").on(
                            exec(http("9. API: Get Subscriber Stats").get("/cat/rest/getSubscriberStats").check(status().is(200)))
                    )
    );

    // --- 3. SUBSCRIBER MANAGEMENT ---
    // --- 3. SUBSCRIBER MANAGEMENT (ALLOCATE) ---
    private static final ChainBuilder subscriberManagement = group("Step 03: Subscriber Management - Allocate").on(
            exec(http("10. API: Get All Agency Subs List")
                    .post("/cat/rest/getAllAgencySubsList")
                    .header("Content-Type", "application/json")
                    .body(StringBody("{\"hierarchy_id\":\"\",\"filterType\":0,\"offset\":\"0\",\"rowsPerPage\":200,\"clientTypeFilter\":[],\"OrgFilter\":[],\"searchString\":\"\"}"))
                    .check(status().is(200)))
                    .pause(1, 3)

                    .exec(http("11. API: Get Corp Hierarchy")
                            .post("/cat/rest/getCorpHierarchy")
                            .header("Content-Type", "application/json")
                            .body(StringBody("{}"))
                            .check(status().is(200)))
                    .pause(1, 3)

                    .exec(http("12. API: Allocate Subscribers")
                            .post("/cat/rest/allocateSubs")
                            .header("Content-Type", "application/json")
                            .body(StringBody("{\"allocationList\":[{\"hierarchy_id\":\"#{hierarchy_id}\",\"mdnList\":[{\"mdn\":[\"#{mdn}\"]}]}],\"action\":\"allocate\"}"))
                            .check(status().is(200)))
    );
    // --- 3b. NEW: REVOKE / UNALLOCATE WORKFLOW ---
    private static final ChainBuilder revokeSubscribers = group("Step 03b: Subscriber Management - Revoke").on(
            exec(http("12b. API: Unallocate Subscribers")
                    .post("/cat/rest/unAllocateSubscriberList")
                    .header("Content-Type", "application/json")
                    .header("Accept-Language", "en_US")
                    .header("Origin", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                    .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com/cat/static/cobalt-ngcatui/index.html")
                    // 🌟 Map the data array exactly as requested in the cURL payload
                    .body(StringBody("{"
                            + "\"unAllocationList\":[{"
                            + "\"hierarchy_id\":\"#{hierarchy_id}\","
                            + "\"mdnList\":[\"#{mdn}\"]"
                            + "}]"
                            + "}"))
                    .check(status().is(200)))
    );


    // --- 4. LOGOUT GROUP ---
    private static final ChainBuilder logoutGroup = group("Step 04: Session Cleanup").on(
            exec(http("13. Logout").get("/cat/view/logout").check(status().in(200, 302)))
                    .exec(flushCookieJar())
    );

    // --- SCENARIO DEFINITION ---
    private final ScenarioBuilder scn = scenario("CAT E2E User Lifecycle - Subscriber Management")
            .feed(userFeeder)
            .exitBlockOnFail().on(
                    exec(loginGroup)
                            .pause(2, 4)
                            .exec(baselinePageLoad)
                            .pause(2, 4)
                            .exec(subscriberManagement)
                            // 🌟 VISUAL DEMO ANCHOR POINT
                            // This stops execution right after allocation hits the server.
                            // Switch to your Web Browser tab here and show the "Activated / Allocated" data state.
                            // 🌟 Print a flashing alert in your terminal logs when it is time to switch windows
                            .exec(session -> {
                                System.out.println(">>>>>>>>>> ALLOCATE COMPLETE: SWITCH TO BROWSER NOW! <<<<<<<<<<");
                                return session;
                            })
                            .pause(5)
                            .exec(revokeSubscribers)
                            .pause(2, 4)
                            .exec(logoutGroup)
            );

    // --- SIMULATION CONFIGURATION ---
    {
        setUp(
                scn.injectOpen(
                        atOnceUsers(5)
                )
        ).protocols(httpProtocol);
    }

    // --- SIMULATION CONFIGURATION ---
//    {
//        setUp(
//                scn.injectOpen(
//                        nothingFor(2),
//                        // 🌟 FIXED: Eases 1,000 entries into the pipeline evenly across 5 minutes
//                        // This prevents server-side resource saturation or immediate rate limits
//                        rampUsers(1000).during(300)
//                )
//        ).protocols(httpProtocol);
//    }
}