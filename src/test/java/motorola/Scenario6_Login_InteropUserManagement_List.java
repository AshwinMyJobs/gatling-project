package motorola;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Meaningful Name: Scenario6_Login_InteropUserManagement
 * Purpose: Dedicated End-to-End Test for user authentication, data bootstrapping,
 *          and dynamic Interop Master List Info queries.
 */
public class Scenario6_Login_InteropUserManagement_List extends Simulation {

    private final FeederBuilder<String> userFeeder = csv("interop_user_management_list.csv").circular();

    private HttpProtocolBuilder httpProtocol = http
            .baseUrl("https://wms-dev-xdmauto.msiidcitgcloud.com")
            .wsBaseUrl("wss://://wms-dev-xdmauto.msiidcitgcloud.com")
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

    // --- 3. INTEROP USER MANAGEMENT ---
    private static final ChainBuilder interopUserManagement = group("Step 03: Interop User Management").on(
            exec(http("10. API: Get Master List Info [INTEROP]")
                    .post("/cat/rest/getMasterList/getMasterListInfo")
                    .header("Content-Type", "application/json")
                    .header("Accept-Language", "en_US")
                    .header("Origin", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                    .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com/cat/static/cobalt-ngcatui/index.html")
                    .body(StringBody("{"
                            + "\"rowsPerPage\":\"200\","
                            + "\"pageNumber\":\"1\","
                            + "\"searchString\":\"\","
                            + "\"advanceSearchParams\":{"
                            + "\"mdn\":\"\",\"displayName\":\"\",\"serviceAuthStatus\":\"\",\"subscriberType\":\"\","
                            + "\"banId\":\"\",\"fanId\":\"\",\"fanName\":\"\",\"banName\":\"\","
                            + "\"interopFeature\":\"\",\"subsUserID\":\"\",\"isLinkedGrp\":\"\""
                            + "},"
                            + "\"offset\":\"0\","
                            + "\"userType\":\"lmrData\","
                            + "\"hierarchy_id\":\"#{hierarchy_id}\","
                            + "\"lastDisplayNameWithMdn\":\"\""
                            + "}"))
                    .check(status().is(200))
                    // 👇 Captures the server body to print out what data is returned
                    .check(bodyString().saveAs("rawInteropResponse")))

                    // 👇 Temporary print block to verify that your data records aren't empty
                    .exec(session -> {
                        System.out.println("\n==============================================");
                        System.out.println("LOGGED IN AS USER: " + session.getString("username"));
                        System.out.println("--- INTEROP USER MASTER LIST RESPONSE ---");
                        System.out.println(session.get("rawInteropResponse") != null ? session.getString("rawInteropResponse") : "NO RESP");
                        System.out.println("==============================================\n");
                        return session;
                    })
    );

    // --- 4. LOGOUT GROUP ---
    private static ChainBuilder logoutGroup = group("Step 04: Session Cleanup").on(
            exec(http("11. Logout").get("/cat/view/logout").check(status().in(200, 302)))
                    .exec(flushCookieJar())
    );

    // --- SCENARIO DEFINITION ---
    private ScenarioBuilder scn = scenario("Interop User Management Simulation")
            .feed(userFeeder)
            .exec(loginGroup)
            .pause(2)
            .exec(baselinePageLoad)
            .pause(2)
            .exec(interopUserManagement)
            .pause(1)
            .exec(logoutGroup);

    {
        setUp(
                scn.injectOpen(atOnceUsers(1))
        ).protocols(httpProtocol);
    }
}
