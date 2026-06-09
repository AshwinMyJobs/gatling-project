package motorola;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Purpose: Verification Run - Auth, Full Baseline Bootstrapping, and Group Profile Transactions.
 */
public class Scenario4_Login_GroupProfileManagement_Create extends Simulation {

    private final FeederBuilder<String> feeder = csv("group_profile_management_list.csv").circular();

    private HttpProtocolBuilder httpProtocol = http
            .baseUrl("https://wms-dev-xdmauto.msiidcitgcloud.com")
            .wsBaseUrl("wss://wms-dev-xdmauto.msiidcitgcloud.com")
            .header("Host", "wms-dev-xdmauto.msiidcitgcloud.com")
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
    // --- 3. GROUP PROFILE PROCESSING ---
    private static final ChainBuilder groupProfileActions = group("Step 03: Group Profile Processing").on(
            // Action A: Create Target Group Profile with a dynamic safe name logic layer
            exec(session -> {
                long timestamp = System.currentTimeMillis();
                return session.set("dynamicProfileName", "Profile_" + timestamp);
            })
                    .exec(http("10. API: Create Group Profile")
                            .post("/cat/rest/createGroupProfile")
                            .header("Content-Type", "application/json")
                            .header("Origin", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                            .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com/cat/static/cobalt-ngcatui/index.html")
                            .body(StringBody("{"
                                    + "\"grpProfileInfo\":{"
                                    + "\"grpProfileName\":\"#{dynamicProfileName}\","
                                    + "\"grpType\":\"1\","
                                    + "\"grpAvatar\":\"0\","
                                    + "\"grpOSMListId\":\"\","
                                    + "\"audioCutIn\":0,"
                                    + "\"mcxGroup\":\"0\","
                                    + "\"grpShared\":\"0\","
                                    + "\"grpSharedCorpList\":[]"
                                    + "}"
                                    + "}"))
                            .check(status().is(200))
                            .check(bodyString().saveAs("createResponse")))

                    .exec(session -> {
                        System.out.println("\nDEBUG >>> Creation Response: " + session.getString("createResponse"));
                        return session;
                    })

                    .pause(2)

                    // Action B: Fetch Group Profile Validation List with fully configured layout criteria
                    .exec(http("11. API: Get Group Profile List")
                            .post("/cat/rest/getGroupProfileList")
                            .header("Content-Type", "application/json")
                            .header("Origin", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                            .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com/cat/static/cobalt-ngcatui/index.html")
                            // 👇 FIXED: Supplied verified target layout parameters instead of empty context brackets
                            .body(StringBody("{"
                                    + "\"pageNumber\":\"1\","
                                    + "\"fetchSize\":\"100\""
                                    + "}"))
                            .check(status().is(200))
                            .check(bodyString().saveAs("listResponse")))

                    .exec(session -> {
                        System.out.println("DEBUG >>> Profile List Context Output:");
                        System.out.println(session.get("listResponse") != null ? session.getString("listResponse") : "EMPTY");
                        System.out.println("==============================================\n");
                        return session;
                    })
    );

    // --- SCENARIO LIFECYCLE DEFINITION ---
    private ScenarioBuilder scn = scenario("CAT E2E User Lifecycle - Group Profile Management")
            .feed(feeder)
            .exec(loginGroup)
            .pause(2)
            .exec(baselinePageLoad)
            .pause(2)
            .exec(groupProfileActions);

    {
        setUp(
                scn.injectOpen(atOnceUsers(1))
        ).protocols(httpProtocol);
    }
}
