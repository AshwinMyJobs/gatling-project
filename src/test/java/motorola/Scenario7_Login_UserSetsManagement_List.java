package motorola;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Meaningful Name: Scenario7_Login_UserSetsManagement
 * Purpose: Dedicated End-to-End Test for user authentication, data bootstrapping,
 *          and User Sets / Sublists retrieval optimization.
 */
public class Scenario7_Login_UserSetsManagement_List extends Simulation {

    // 1. Endless loop data feeder referencing your configured CSV asset
    private final FeederBuilder<String> userFeeder = csv("user_set_management_list.csv").circular();

    // 2. HTTP Base Options locked to your exact subdomain environment
    private HttpProtocolBuilder httpProtocol = http
            .baseUrl("https://wms-dev-xdmauto.msiidcitgcloud.com")
            .wsBaseUrl("wss://wms-dev-xdmauto.msiidcitgcloud.com")
            .header("Host", "wms-dev-xdmauto.msiidcitgcloud.com")
            .acceptHeader("application/json, text/plain, */*")
            .acceptLanguageHeader("en-US,en;q=0.9")
            .userAgentHeader("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .disableCaching()
            .disableAutoReferer()
            // --- CRITICAL CONNECTION FIXES FOR PREMATURE CLOSE ---
            .connectionHeader("keep-alive")
            .shareConnections()
            .maxConnectionsPerHost(6);

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
                    .exec(http("5. API: Refresh Token").post("/cat/view/refreshToken").body(StringBody("{}")).check(status().is(200)))
                    .exec(http("6. API: Get Users Permissions")
                            .post("/cat/rest/getUsersPermissions")
                            .header("Content-Type", "application/json")
                            // 👇 FIXED: Changed corpName from AshwinCorp to CorpN1
                            .body(StringBody("{\"agencyInfo\":{\"corpName\":\"CorpN1\"},\"userIdList\":[\"#{username}\"]}"))
                            .check(status().is(200)))
                    .exec(http("7. API: Sync Master List Info").post("/cat/rest/syncMasterListInfo").check(status().is(200)))
                    .exec(http("8. API: Get All Async Events").get("/cat/rest/getAllAsyncEvents").check(status().is(200)))
                    .repeat(4, "i").on(
                            exec(http("9. API: Get Subscriber Stats").get("/cat/rest/getSubscriberStats").check(status().is(200)))
                    )
    );

    // --- 3. USER SETS MANAGEMENT ---
    private static final ChainBuilder userSetsManagement = group("Step 03: User Sets Processing").on(
            exec(http("10. API: Get All User Sublists")
                    .post("/cat/rest/getAllSublists")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "en_US")
                    .header("Origin", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                    .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com/cat/static/cobalt-ngcatui/index.html")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                    .body(StringBody("{"
                            + "\"pageNumber\":\"0\","
                            + "\"fetchSize\":\"200\""
                            + "}"))
                    .check(status().is(200))
                    // 👇 Captures response text payload to confirm content presence
                    .check(bodyString().saveAs("rawUserSets")))

                    // 👇 Added console log block to check your newly created User Set payload structure
                    .exec(session -> {
                        System.out.println("\n==============================================");
                        System.out.println("LOGGED IN AS VIRTUAL USER: " + session.getString("username"));
                        System.out.println("--- USER SETS / SUBLISTS STRING RESPONSE ---");
                        System.out.println(session.get("rawUserSets") != null ? session.getString("rawUserSets") : "NO DATA RESPONSE FOUND");
                        System.out.println("==============================================\n");
                        return session;
                    })
    );

    // 4. SCENARIO DEFINITION
    private ScenarioBuilder scn = scenario("User Sets Simulation")
            .feed(userFeeder)
            .exec(loginGroup)
            .pause(2)
            .exec(baselinePageLoad)
            .pause(2)
            .exec(userSetsManagement);

    // 5. INJECTION PROFILE
    {
        setUp(
                scn.injectOpen(
                        atOnceUsers(1)
                )
        ).protocols(httpProtocol);
    }
}
