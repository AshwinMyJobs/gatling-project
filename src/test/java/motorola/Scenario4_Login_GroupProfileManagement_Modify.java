package motorola;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Meaningful Name: Scenario4_Login_GroupProfileManagement_Modify
 * Purpose: Dedicated End-to-End Test for Group Profile list extraction
 *          and subsequent dynamic transaction modifications.
 */
public class Scenario4_Login_GroupProfileManagement_Modify extends Simulation {

    private final FeederBuilder<String> feeder = csv("group_profile_management_modify.csv").circular();

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
    // --- 3b. GROUP PROFILE INVENTORY FETCH ---
    private static final ChainBuilder fetchGroupProfiles = group("Step 03b: Fetch Group Profiles").on(
            exec(http("12. API: Get Group Profile List")
                    .post("/cat/rest/getGroupProfileList")
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                    .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com/cat/static/cobalt-ngcatui/index.html")
                    .body(StringBody("{"
                            + "\"pageNumber\":\"1\","
                            + "\"fetchSize\":\"100\""
                            + "}"))
                    .check(status().is(200))
                    // 👇 Automatically extracts the top array profile parameters dynamically from your inventory results
                    .check(jsonPath("$.grpProfileList.grpProfileDetails[0].grpProfileId").saveAs("targetProfileId"))
                    .check(jsonPath("$.grpProfileList.grpProfileDetails[0].grpProfileName").saveAs("targetProfileName")))
    );

    // --- 3c. MODIFY GROUP PROFILE GROUP ---
    private static ChainBuilder modifyGroupProfile = group("Step 03c: Modify Group Profile").on(
            exec(http("13. API: Modify Group Profile")
                    .post("/cat/rest/modifyGroupProfile")
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                    .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com/cat/static/cobalt-ngcatui/index.html")
                    // Mapped precisely to your raw JSON cURL schema arguments context
                    .body(StringBody("{"
                            + "\"grpProfileInfo\":{"
                            + "\"grpProfileName\":\"#{targetProfileName}\","
                            + "\"newGrpProfileName\":\"#{targetProfileName}-modified\","
                            + "\"grpProfileId\":\"#{targetProfileId}\","
                            + "\"grpType\":\"1\","
                            + "\"grpAvatar\":\"0\","
                            + "\"grpOSMListId\":\"\","
                            + "\"audioCutIn\":0,"
                            + "\"mcxGroup\":\"0\","
                            + "\"overrideDND\":null,"
                            + "\"grpShared\":\"0\","
                            + "\"grpSharedCorpList\":[],"
                            + "\"ugwInterop\":null"
                            + "}"
                            + "}"))
                    .check(status().is(200)))
    );

    // --- SCENARIO LIFECYCLE DEFINITION ---
    private ScenarioBuilder scn = scenario("Group Profiles Modification Run")
            .feed(feeder)
            .exec(loginGroup)
            .pause(2)
            .exec(baselinePageLoad)
            .pause(2)
            .exec(fetchGroupProfiles)
            .pause(2)
            .exec(modifyGroupProfile);

    {
        setUp(
                scn.injectOpen(atOnceUsers(1))
        ).protocols(httpProtocol);
    }
}
