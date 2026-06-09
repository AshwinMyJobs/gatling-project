package motorola;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Meaningful Name: Scenario8_Login_CorpHierarchyManagement
 * Purpose: Dedicated End-to-End Test for Corporate Hierarchy traversal
 *          and dynamic hierarchy user mapping verification.
 */
public class Scenario8_Login_CorpHierarchyManagement extends Simulation {

    private final FeederBuilder<String> userFeeder = csv("corp_hierarchy_management.csv").circular();

    private HttpProtocolBuilder httpProtocol = http
            .baseUrl("https://wms-dev-xdmauto.msiidcitgcloud.com")
            .wsBaseUrl("wss://wms-dev-xdmauto.msiidcitgcloud.com")
            .acceptHeader("application/json, text/plain, */*")
            .acceptLanguageHeader("en-US,en;q=0.9")
            .userAgentHeader("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .disableCaching()
            .shareConnections();

    // --- 1. LOGIN GROUP ---
    private static ChainBuilder loginGroup = group("Step 01: Auth & Login").on(
            exec(flushCookieJar()).
                    exec(http("1. Initial Redirect")
                            .get("/cat/view/idmlogin")
                            .disableFollowRedirect()
                            .check(status().is(302), header("Location").saveAs("keycloakUrl")))

                    .exec(http("2. Load Keycloak Page")
                            .get(session -> session.getString("keycloakUrl"))
                            .check(status().is(200), regex("var loginAction = '([^']*)'").saveAs("rawLoginAction")))

                    .exec(session -> {
                        String raw = session.getString("rawLoginAction");
                        return session.set("loginAction", raw != null ? raw.replace("&amp;", "&") : "");
                    })

                    .exec(http("3. Submit Credentials")
                            .post(session -> session.getString("loginAction"))
                            .formParam("username", "#{username}")
                            .formParam("password", "#{password}")
                            .formParam("login", "log in")
                            .disableFollowRedirect()
                            .check(status().is(302), header("Location").saveAs("callbackUrl")))

                    .exec(http("3b. Keycloak Callback Handshake")
                            .get(session -> session.getString("callbackUrl"))
                            .check(status().is(200)))
    );
    // --- 2. BASELINE PAGE LOAD GROUP ---
    private static final ChainBuilder baselinePageLoad = group("Step 02: Initial Page Load Baseline").on(
            exec(http("4. API: Get Global Data").post("/cat/rest/getGlobalData").check(status().is(200)))
                    // 👇 FIXED: Added the missing /cat/ prefix path to stop the premature connection socket closure
                    .exec(http("5. API: Refresh Token").post("/cat/view/refreshToken").body(StringBody("{}")).check(status().is(200)))
                    .exec(http("6. API: Get Users Permissions")
                            .post("/cat/rest/getUsersPermissions")
                            .header("Content-Type", "application/json")
                            .body(StringBody("{\"agencyInfo\":{\"corpName\":\"CorpN1\"},\"userIdList\":[\"#{username}\"]}"))
                            .check(status().is(200)))
                    .exec(http("7. API: Sync Master List Info").post("/cat/rest/syncMasterListInfo").check(status().is(200)))
                    .exec(http("8. API: Get All Async Events").get("/cat/rest/getAllAsyncEvents").check(status().is(200)))
                    .repeat(4, "i").on(
                            exec(http("9. API: Get Subscriber Stats").get("/cat/rest/getSubscriberStats").check(status().is(200)))
                    )
    );

    // --- 3. CORPORATE HIERARCHY PROCESSING (PRINT ONLY) ---
    private static final ChainBuilder corpHierarchyProcessing = group("Step 03: Corporate Hierarchy Processing").on(
            // Call 1: Fetch the core corporate tree structure
            exec(http("10. API: Get Corporate Hierarchy")
                    .post("/cat/rest/getCorpHierarchy")
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                    .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com/cat/static/cobalt-ngcatui/index.html")
                    .body(StringBody("{}"))
                    .check(status().is(200))
                    // 👇 Captures raw response body string cleanly
                    .check(bodyString().saveAs("rawHierarchyTree")))

                    .pause(1)

                    // 👇 Decodes and dumps the full structural data tree on your console screen
                    .exec(session -> {
                        System.out.println("\n==============================================");
                        System.out.println("--- RAW CORPORATE HIERARCHY TREE LOG DUMP ---");
                        System.out.println(session.getString("rawHierarchyTree"));
                        System.out.println("==============================================\n");
                        return session;
                    })

                    // Call 2: Query users nested within that corporate branch level (using fallback)
                    .exec(http("11. API: Get Hierarchy Users")
                            .post("/cat/rest/getHierarchyUsers")
                            .header("Content-Type", "application/json")
                            .header("Origin", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                            .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com/cat/static/cobalt-ngcatui/index.html")
                            .body(StringBody("{\"hierarchy_id\":\"00000142\"}")) // Standard working default fallback
                            .check(status().is(200)))
    );


    // --- 4. LOGOUT GROUP ---
    private static ChainBuilder logoutGroup = group("Step 04: Session Cleanup").on(
            exec(http("12. Logout").get("/cat/view/logout").check(status().in(200, 302)))
                    .exec(flushCookieJar())
    );

    // --- SCENARIO LIFECYCLE DEFINITION ---
    private ScenarioBuilder scn = scenario("Corporate Hierarchy Management Simulation")
            .feed(userFeeder)
            .exec(loginGroup)
            .pause(2)
            .exec(baselinePageLoad)
            .pause(2)
            .exec(corpHierarchyProcessing)
            .pause(1)
            .exec(logoutGroup);

    {
        setUp(
                scn.injectOpen(atOnceUsers(1))
        ).protocols(httpProtocol);
    }
}
