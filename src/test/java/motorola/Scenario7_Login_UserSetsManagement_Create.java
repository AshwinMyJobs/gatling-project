package motorola;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Meaningful Name: Scenario7_Login_UserSetsManagement_Create
 * Purpose: Dedicated End-to-End Test for User Sets sequential creation
 *          and operational overview list inventory verification.
 */
public class Scenario7_Login_UserSetsManagement_Create extends Simulation {

    private final FeederBuilder<String> userFeeder = csv("user_set_management_create.csv").circular();

    // 👇 FIXED: Removed malformed protocol prefixes from domain validation fields
    private HttpProtocolBuilder httpProtocol = http
            .baseUrl("https://wms-dev-xdmauto.msiidcitgcloud.com")
            .wsBaseUrl("wss://wms-dev-xdmauto.msiidcitgcloud.com")
            .header("Host", "wms-dev-xdmauto.msiidcitgcloud.com")
            .acceptHeader("application/json, text/plain, */*")
            .acceptLanguageHeader("en-US,en;q=0.9")
            .userAgentHeader("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .disableCaching()
            .disableAutoReferer()
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
                            .body(StringBody("{\"agencyInfo\":{\"corpName\":\"CorpN1\"},\"userIdList\":[\"#{username}\"]}"))
                            .check(status().is(200)))
                    .exec(http("7. API: Sync Master List Info").post("/cat/rest/syncMasterListInfo").check(status().is(200)))
                    .exec(http("8. API: Get All Async Events").get("/cat/rest/getAllAsyncEvents").check(status().is(200)))
                    .repeat(4, "i").on(
                            exec(http("9. API: Get Subscriber Stats").get("/cat/rest/getSubscriberStats").check(status().is(200)))
                    )
    );

    // --- 3a. CREATE USER SET GROUP ---
    private static ChainBuilder createUserSet = group("Step 03a: Create New User Set").on(
            exec(session -> {
                long timestamp = System.currentTimeMillis();
                return session.set("dynamicUserSetName", "UserSet_" + timestamp);
            })
                    .exec(http("11. API: Create Sublist")
                            .post("/cat/rest/createSublist")
                            .header("Content-Type", "application/json")
                            .header("Accept-Language", "en_US")
                            .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                            .body(StringBody("{"
                                    + "\"addedMDNListIds\":[\"9300050122\"],"
                                    + "\"newSublistName\":\"#{dynamicUserSetName}\","
                                    + "\"addedSublistIds\":[],"
                                    + "\"mdnlist\":[],"
                                    + "\"listType\":null,"
                                    + "\"listDistribution\":0"
                                    + "}"))
                            .check(status().is(200)))
    );

    // --- 3b. USER SETS INVENTORY FETCH ---
    private static final ChainBuilder userSetsManagement = group("Step 03b: User Sets Inventory Fetch").on(
            exec(http("12. API: Get All User Sublists")
                    .post("/cat/rest/getAllSublists")
                    .header("Content-Type", "application/json")
                    .header("Accept-Language", "en_US")
                    .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                    .body(StringBody("{"
                            + "\"pageNumber\":\"0\","
                            + "\"fetchSize\":\"200\""
                            + "}"))
                    .check(status().is(200))
                    .check(bodyString().saveAs("rawUserSets")))

                    .exec(session -> {
                        System.out.println("\n==============================================");
                        System.out.println("LOGGED IN AS VIRTUAL USER: " + session.getString("username"));
                        System.out.println("--- USER SETS / SUBLISTS STRING RESPONSE ---");
                        System.out.println(session.get("rawUserSets") != null ? session.getString("rawUserSets") : "NO DATA RESPONSE FOUND");
                        System.out.println("==============================================\n");
                        return session;
                    })
    );

    // 4. SCENARIO LIFECYCLE ROUTING DEFINITION
    private ScenarioBuilder scn = scenario("User Sets Simulation Lifecycle Flow")
            .feed(userFeeder)
            .exec(loginGroup)
            .pause(2)
            .exec(baselinePageLoad)
            .pause(2)
            .exec(createUserSet)
            .pause(2)
            .exec(userSetsManagement);

    // 5. RUN SETTINGS INJECTION PROFILE
    {
        setUp(
                scn.injectOpen(
                        atOnceUsers(1)
                )
        ).protocols(httpProtocol);
    }
}
