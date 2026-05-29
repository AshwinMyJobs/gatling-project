package motorola;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Meaningful Name: Scenario4_Login_GroupProfileManagement
 * Purpose: Dedicated End-to-End Test for user authentication, data bootstrapping,
 *          Group Profile creation tracking, and interactive live demonstration.
 */
public class Scenario4_Login_GroupProfileManagement extends Simulation {

    // 1. Endless loop data feeder targeting your core CSV asset
    private final FeederBuilder<String> userFeeder = csv("talk_group_management.csv").circular();

    // 2. HTTP Base Options locked strictly to your correct subdomain
    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl("https://wms-dev-xdmauto.msiidcitgcloud.com")
            .wsBaseUrl("wss://wms-dev-xdmauto.msiidcitgcloud.com")
            .header("Host", "wms-dev-xdmauto.msiidcitgcloud.com")
            .acceptHeader("application/json, text/plain, */*")
            .acceptLanguageHeader("en-US,en;q=0.9")
            .userAgentHeader("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .disableCaching()
            .connectionHeader("keep-alive")
            .shareConnections()
            .maxConnectionsPerHost(6);

    // --- 1. LOGIN GROUP ---
    private static final ChainBuilder loginGroup = group("Step 01: Auth & Login").on(
            exec(flushCookieJar()).
                    exec(http("1. Initial Redirect")
                            .get("/cat/view/idmlogin")
                            .disableFollowRedirect()
                            .check(status().is(302), header("Location").saveAs("keycloakUrl")))

                    .exec(http("2. Load Keycloak Page")
                            .get(session -> session.getString("keycloakUrl"))
                            .check(status().is(200),
                                    regex("var loginAction = '([^']*)'").saveAs("rawLoginAction"),
                                    regex("execution=([^&]*)").saveAs("executionToken"),
                                    regex("client_id=([^&]*)").saveAs("clientIdToken"),
                                    regex("tab_id=([^&]*)").saveAs("tabIdToken")
                            ))

                    .exec(session -> {
                        String raw = session.getString("rawLoginAction");
                        return session.set("loginAction", raw != null ? raw.replace("&amp;", "&") : "");
                    })

                    .exec(http("3. Submit Credentials")
                            .post(session -> session.getString("loginAction"))
                            .formParam("username", "#{username}")
                            .formParam("password", "#{password}")
                            .formParam("login", "log in")
                            .formParam("execution", "#{executionToken}")
                            .formParam("client_id", "#{clientIdToken}")
                            .formParam("tab_id", "#{tabIdToken}")
                            .disableFollowRedirect()
                            .check(status().is(302), header("Location").saveAs("callbackUrl")))

                    .exec(http("3b. Keycloak Callback Handshake")
                            .get(session -> session.getString("callbackUrl"))
                            .check(status().is(200))) // 🌟 FIXED: Removed the manual check to allow background cookies to pass naturally
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

    // --- 3. GROUP PROFILE MANAGEMENT ---
    private static final ChainBuilder groupProfileManagement = group("Step 03: Group Profile Management").on(
            // Request 10: Fetch Group Profile List records
            exec(http("10. API: Get Group Profile List")
                    .post("/cat/rest/getGroupProfileList")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "en_US")
                    .header("Origin", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                    .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com/cat/static/cobalt-ngcatui/index.html")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                    .body(StringBody("{"
                            + "\"fetchSize\":\"200\","
                            + "\"startIndex\":\"1\""
                            + "}"))
                    .check(status().is(200)))

                    .pause(2)

                    // Request 11: Create Group Profile Step (Fully Aligned to cURL Properties)
                    .exec(session -> session.set("grp_profile_uuid", "GrpProf_" + java.util.UUID.randomUUID().toString().substring(0, 8)))
                    .exec(http("11. API: Create Group Profile")
                            .post("/cat/rest/createGroupProfile")
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json, text/plain, */*")
                            .header("Accept-Language", "en_US")
                            .header("Origin", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                            .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com/cat/static/cobalt-ngcatui/index.html")
                            .header("Sec-Fetch-Dest", "empty")
                            .header("Sec-Fetch-Mode", "cors")
                            .header("Sec-Fetch-Site", "same-origin")
                            .header("sec-ch-ua", "\"Chromium\";v=\"148\", \"Google Chrome\";v=\"148\", \"Not/A)Brand\";v=\"99\"")
                            .header("sec-ch-ua-mobile", "?0")
                            .header("sec-ch-ua-platform", "\"Windows\"")
                            .body(StringBody("{"
                                    + "\"grpProfileInfo\":{"
                                    + "\"grpProfileName\":\"#{grp_profile_uuid}\","
                                    + "\"grpType\":\"1\","
                                    + "\"grpAvatar\":\"0\","
                                    + "\"grpOSMListId\":\"\","
                                    + "\"audioCutIn\":0,"
                                    + "\"mcxGroup\":\"0\","
                                    + "\"grpShared\":\"0\","
                                    + "\"grpSharedCorpList\":[]"
                                    + "}"
                                    + "}"))
                            .check(status().is(200), bodyString().saveAs("createGroupProfileResponse")))

                    // Terminal printer to parse tracking confirmations directly inside console
                    .exec(session -> {
                        String resp = session.getString("createGroupProfileResponse");
                        System.out.println("\n\n>>> 🚀 SERVER RESPONSE LOGGER: " + resp + "\n\n");
                        return session;
                    })
                    .pause(2)
    );

    // --- SCENARIO DEFINITION ---
    private final ScenarioBuilder scn = scenario("CAT E2E User Lifecycle - Group Profile Management Live Demo")
            .feed(userFeeder)
            .exitBlockOnFail().on(
                    exec(loginGroup)
                            .pause(1)
                            .exec(baselinePageLoad)
                            .pause(1)
                            .exec(groupProfileManagement)
            );

    {
        setUp(scn.injectOpen(atOnceUsers(1))).protocols(httpProtocol);
    }
}