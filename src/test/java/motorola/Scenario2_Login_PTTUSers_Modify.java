package motorola;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Purpose: Verification Run - Auth, Baseline, Live PTT Users Fetch, and Dynamic Subscriber Modification.
 */
public class Scenario2_Login_PTTUSers_Modify extends Simulation {

    private FeederBuilder<String> userFeeder = csv("pttusers.csv").circular();

    private HttpProtocolBuilder httpProtocol = http
            .baseUrl("https://wms-dev-xdmauto.msiidcitgcloud.com")
            .acceptHeader("application/json, text/plain, */*")
            .acceptLanguageHeader("en-US,en;q=0.9")
            .userAgentHeader("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("sec-ch-ua", "\"Google Chrome\";v=\"120\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"120\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
            .header("upgrade-insecure-requests", "1")
            .disableCaching()
            .shareConnections();

    // --- 1. LOGIN GROUP ---
    private static ChainBuilder loginGroup = group("Step 01: Auth & Login").on(
            exec(http("1. Initial Redirect")
                    .get("/cat/view/idmlogin")
                    .disableFollowRedirect()
                    .check(status().is(302),
                            header("Location").saveAs("keycloakUrl")))
                    .exec(http("2. Load Keycloak Page")
                            .get(session -> session.getString("keycloakUrl"))
                            .check(status().is(200),
                                    regex("var loginAction = '([^']*)'").saveAs("rawLoginAction")))
                    .exec(session -> {
                        String raw = session.getString("rawLoginAction");
                        return session.set("loginAction", raw.replace("&amp;", "&"));
                    })
                    .exec(http("3. Submit Credentials")
                            .post(session -> session.getString("loginAction"))
                            .formParam("username", "#{username}")
                            .formParam("password", "#{password}")
                            .formParam("login", "log in")
                            .check(status().in(200, 302)))
    );

    // --- 2. BASELINE PAGE LOAD GROUP ---
    private static ChainBuilder baselinePageLoad = group("Step 02: Initial Page Load Baseline").on(
            exec(http("4. API: Get Global Data")
                    .post("/cat/rest/getGlobalData")
                    .check(status().is(200))
                    .check(jsonPath("$..hierarchy_id").optional().saveAs("idFromGlobalData")))
                    .exec(http("5. API: Refresh Token")
                            .post("/cat/view/refreshToken")
                            .body(StringBody("{}"))
                            .check(status().is(200)))
                    .exec(http("6. API: Get Users Permissions")
                            .post("/cat/rest/getUsersPermissions")
                            .header("Content-Type", "application/json")
                            .body(StringBody("{\"agencyInfo\":{\"corpName\":\"CorpN1\"},\"userIdList\":[\"#{username}\"]}"))
                            .check(status().is(200))
                            .check(jsonPath("$..hierarchy_id").optional().saveAs("idFromPermissions")))
                    .exec(http("7. API: Sync Master List Info")
                            .post("/cat/rest/syncMasterListInfo")
                            .check(status().is(200)))
                    .exec(http("8. API: Get All Async Events")
                            .get("/cat/rest/getAllAsyncEvents")
                            .check(status().is(200)))
                    .repeat(4, "i").on(
                            exec(http("9. API: Get Subscriber Stats")
                                    .get("/cat/rest/getSubscriberStats")
                                    .check(status().is(200)))
                    )
                    .exec(session -> {
                        String finalHierarchyId = null;
                        if (session.contains("idFromGlobalData") && session.getString("idFromGlobalData") != null) {
                            finalHierarchyId = session.getString("idFromGlobalData");
                        } else if (session.contains("idFromPermissions") && session.getString("idFromPermissions") != null) {
                            finalHierarchyId = session.getString("idFromPermissions");
                        }
                        if (finalHierarchyId == null) {
                            finalHierarchyId = "00000142";
                        }
                        return session.set("sessionHierarchyId", finalHierarchyId);
                    })
    );

    // --- 3b. PTT USERS STRESS LOAD ---
    private static ChainBuilder pttUsersStressLoad = group("Step 03b: PTT Users Heavy Fetch").on(
            exec(http("12b. API: Get Master List Info [STRESS]")
                    .post("/cat/rest/getMasterList/getMasterListInfo")
                    .header("Content-Type", "application/json")
                    .header("Accept-Language", "en_US")
                    .body(StringBody("{" +
                            "\"rowsPerPage\":\"50\"," +
                            "\"pageNumber\":\"1\"," +
                            "\"searchString\":\"\"," +
                            "\"advanceSearchParams\":{" +
                            "\"mdn\":\"\",\"displayName\":\"\",\"serviceAuthStatus\":\"\",\"subscriberType\":\"\"," +
                            "\"banId\":\"\",\"fanId\":\"\",\"fanName\":\"\",\"banName\":\"\"," +
                            "\"interopFeature\":\"\",\"subsUserID\":\"\",\"isLinkedGrp\":\"\",\"clientType\":\"\"" +
                            "}," +
                            "\"offset\":\"0\"," +
                            "\"userType\":\"pttUserData\"," +
                            "\"hierarchy_id\":\"#{sessionHierarchyId}\"," +
                            "\"lastDisplayNameWithMdn\":\"\"" +
                            "}"))
                    .check(status().is(200))
                    .check(jsonPath("$.responseData.data[*].mdn").saveAs("targetMdn")))
    );

    // --- 3c. MODIFY PTT USER GROUP ---
    private static ChainBuilder modifyPttUser = group("Step 03c: Modify PTT User Details").on(
            exec(http("13a. API: Save Subscriber Details")
                    .post("/cat/rest/saveSubscriberDetails")
                    .header("Content-Type", "application/json")
                    .header("Accept-Language", "en_US")
                    .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                    .body(StringBody("{" +
                            "\"tagId\":\"23\"," +
                            "\"subscriberMDN\":\"#{targetMdn}\"," +
                            "\"newSubscriberName\":\"#{targetMdn}-modified\"," +
                            "\"newSbscrbrEmail\":null," +
                            "\"newSubscriptionType\":null," +
                            "\"newPoCServiceAuthStatus\":null," +
                            "\"addedMDNListIds\":[]," +
                            "\"removedMDNListIds\":[]," +
                            "\"addedSublistIds\":[]," +
                            "\"removedSublistIds\":[]," +
                            "\"priorityGroups\":null," +
                            "\"assignedCampedGroupIdList\":null," +
                            "\"unAssignedCampedGroupIdList\":null," +
                            "\"subsDetails\":null," +
                            "\"subscrInfoList\":null," +
                            "\"dispatchType\":null," +
                            "\"userId\":null," +
                            "\"subsEmergencyAttributes\":{" +
                            "\"emergInitPermission\":\"0\"," +
                            "\"emergCallType\":null," +
                            "\"emergCancelPermission\":null," +
                            "\"emergOriginBitSet\":\"0\"," +
                            "\"emergTermBitSet\":\"0\"," +
                            "\"emergLMRBehavior\":\"0\"," +
                            "\"emergDestAttributes\":{" +
                            "\"emergDestination\":{\"priDestination\":null,\"secDestination\":null}," +
                            "\"emergDestType\":null" +
                            "}," +
                            "\"emergConfigTimer\":\"3.0\"" +
                            "}," +
                            "\"targetMdnPermList\":null," +
                            "\"isRadioClient\":true," +
                            "\"changeInEmergency\":true," + // 👈 FIXED: Added missing backslash-quote string token escape
                            "\"preClient\":false," +
                            "\"newSbscrbrAliasMdn\":null," +
                            "\"multiZone\":true," +
                            "\"isRecordingreset\":false," +
                            "\"clientSettings\":{\"recordingStatus\":null}," +
                            "\"subscriberClientType\":\"14\"," +
                            "\"mdnList\":null," +
                            "\"deleteCmnSublistId\":[]" +
                            "}"))
                    .check(status().is(200)))

                    .pause(1)

                    .exec(http("13b. API: Update Subscriber Cache")
                            .post("/cat/rest/updateSubscriberDetailsIntoCache")
                            .header("Content-Type", "application/json")
                            .header("Accept-Language", "en_US")
                            .body(StringBody("{\"subscriberMDN\":\"#{targetMdn}\"}"))
                            .check(status().is(200)))
    );

    // --- 4. LOGOUT GROUP ---
    private static ChainBuilder logoutGroup = group("Step 04: Session Cleanup").on(
            exec(http("14. Logout").get("/cat/view/logout").check(status().in(200, 302)))
                    .exec(flushCookieJar())
    );

    // --- SCENARIO DEFINITION ---
    private ScenarioBuilder scn = scenario("CAT E2E User Lifecycle - PTT Users Modification Run")
            .feed(userFeeder)
            .exec(loginGroup)
            .pause(1)
            .exec(baselinePageLoad)
            .pause(2)
            .exec(pttUsersStressLoad)
            .pause(2)
            .exec(modifyPttUser)
            .pause(1)
            .exec(logoutGroup);

    {
        setUp(
                scn.injectOpen(atOnceUsers(1))
        ).protocols(httpProtocol);
    }
}
