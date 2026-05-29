package motorola;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Meaningful Name: Scenario2_Login_TalkGroupManagement
 * Purpose: Advanced End-to-End Test for user authentication, data bootstrapping,
 *          and dynamic Talk Group Management, running continuously for 1 hour.
 */
public class Scenario2_Login_TalkGroupManagement extends Simulation {

    // 1. Endless loop data feeder for safety across the 1-hour test duration
    private final FeederBuilder<String> userFeeder = csv("talk_group_management.csv").circular();

    // 2. HTTP Base Options locked strictly to your correct subdomain
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
                            .check(status().is(200),
                                    regex("var loginAction = '([^']*)'").saveAs("rawLoginAction"),
                                    // 🌟 FIXED: Added context extraction parameters
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
                            // 🌟 FIXED: Submits tracked states back to Keycloak
                            .formParam("execution", "#{executionToken}")
                            .formParam("client_id", "#{clientIdToken}")
                            .formParam("tab_id", "#{tabIdToken}")
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
                    // 🌟 CLEANED: Restored back to your original stable request signature
                    .exec(http("6. API: Get Users Permissions")
                            .post("/cat/rest/getUsersPermissions")
                            .header("Content-Type", "application/json")
                            .body(StringBody("{\"agencyInfo\":{\"corpName\":\"AshwinCorp\"},\"userIdList\":[\"#{username}\"]}"))
                            .check(status().is(200)))
                    .exec(http("7. API: Sync Master List Info").post("/cat/rest/syncMasterListInfo").check(status().is(200)))
                    .exec(http("8. API: Get All Async Events").get("/cat/rest/getAllAsyncEvents").check(status().is(200)))
                    .repeat(4, "i").on(
                            exec(http("9. API: Get Subscriber Stats").get("/cat/rest/getSubscriberStats").check(status().is(200)))
                    )
    );



    // --- 3. TALK GROUPS MANAGEMENT (Clean List-Only Version) ---
    private static final ChainBuilder listTalkGroups = group("Step 03: Manage Contacts and Talkgroups").on(
            exec(http("11. API: Get Group List")
                    .post("/cat/rest/getGroupList")
                    .header("Content-Type", "application/json")
                    .header("Accept-Language", "en_US")
                    .header("Origin", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                    .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                    .body(StringBody("{\"forceReload\":\"true\",\"pageNumber\":0,\"fetchSize\":\"200\"}"))
                    .check(status().is(200), bodyString().saveAs("getGroupListResponse")))

                    // 🌟 SAFE COUNTER & PRINTER: Won't crash if no groups were created
                    .exec(session -> {
                        String listResp = session.getString("getGroupListResponse");
                        System.out.println("\n=======================================================");
                        System.out.println(">>> 📊 DIRECT FETCH GROUP LIST AUDIT <<<");
                        System.out.println("=======================================================");

                        if (listResp == null) {
                            System.out.println("Server returned an empty or null response body.");
                        } else {
                            // Check for our tracking prefix anywhere inside the raw response text
                            boolean containsGatlingGroups = listResp.contains("TTTGP_Proof_");
                            System.out.println("Raw Response Length: " + listResp.length() + " characters.");
                            System.out.println("Contains any 'TTTGP_Proof_' groups? -> " + (containsGatlingGroups ? "YES" : "NO"));
                        }
                        System.out.println("=======================================================\n");
                        return session;
                    })
                    .pause(2)
    );

    // --- 4. CREATE TALK GROUP WORKFLOW (Generates & Tracks 10 items) ---
    private static final ChainBuilder createTalkGroup = group("Step 04: Create New Talk Group").on(
            // Initialize an empty tracking list into the session before beginning the loop
            exec(session -> session.set("created_ids_list", new java.util.ArrayList<String>()))
                    .repeat(10, "createCounter").on(
                            exec(session -> session.set("group_uuid", "TTTGP_Proof_" + java.util.UUID.randomUUID().toString().substring(0, 8)))
                                    .exec(http("12. API: Create Group")
                                            .post("/cat/rest/createGroup")
                                            .header("Content-Type", "application/json")
                                            .header("Accept", "application/json, text/plain, */*")
                                            .header("Accept-Language", "en_US")
                                            .header("Origin", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                                            .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                                            .header("Sec-Fetch-Dest", "empty")
                                            .header("Sec-Fetch-Mode", "cors")
                                            .header("Sec-Fetch-Site", "same-origin")
                                            .header("sec-ch-ua", "\"Chromium\";v=\"148\", \"Google Chrome\";v=\"148\", \"Not/A)Brand\";v=\"99\"")
                                            .header("sec-ch-ua-mobile", "?0")
                                            .header("sec-ch-ua-platform", "\"Windows\"")
                                            .body(StringBody("{"
                                                    + "\"groupMembers\":[],"
                                                    + "\"addedSublistIds\":[],"
                                                    + "\"newGroupname\":\"#{group_uuid}\","
                                                    + "\"groupType\":\"1\","
                                                    + "\"sublistMemberProperties\":[],"
                                                    + "\"removedMDNListIds\":[],"
                                                    + "\"modifiedMembers\":[],"
                                                    + "\"removedSublistIds\":[],"
                                                    + "\"contactPairing\":false,"
                                                    + "\"corpName\":\"AshwinCorp\","
                                                    + "\"hierarchyId\":\"#{hierarchy_id}\""
                                                    + "}"))
                                            // 🌟 FIXED PATH: Added [0] array index to target the creation block accurately
                                            .check(status().is(200),
                                                    jsonPath("$.responseData.data[0].groupId").saveAs("temp_id")))

                                    // Appends the server group ID to our tracker array list
                                    .exec(session -> {
                                        String newId = session.getString("temp_id");
                                        java.util.List<String> currentList = new java.util.ArrayList<>(session.getList("created_ids_list"));
                                        currentList.add(newId);

                                        int progress = session.getInt("createCounter") + 1;
                                        System.out.println(">>> [CREATE LOOP " + progress + "/10] Saved Server ID: " + newId + " (" + session.getString("group_uuid") + ")");

                                        return session.set("created_ids_list", currentList);
                                    })
                                    .pause(1)
                    )
    );


    // --- 5. UPDATE TALK GROUP WORKFLOW (Modifies all 10 created records) ---
    private static final ChainBuilder updateTalkGroup = group("Step 05: Modify Talk Group Details").on(
            // Loops dynamically over your 10 captured in-memory database IDs
            foreach("#{created_ids_list}", "target_id", "updateCounter").on(
                    exec(session -> {
                        // Generate a clean modified tracking name variation for each loop step
                        int orderNum = session.getInt("updateCounter") + 1;
                        return session.set("updated_name", "Gatling_Mod_Asset_" + orderNum);
                    })
                            .exec(http("13. API: Modify Group Details")
                                    .post("/cat/rest/modifyGroupDetails") // 🌟 Real matching endpoint path
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
                                    // 🌟 REPLICATED DATA RAW SCHEMA: Matches your exact schema properties
                                    .body(StringBody("{"
                                            + "\"groupId\":\"#{target_id}\"," // Dynamically updates each target ID
                                            + "\"groupMembers\":[],"
                                            + "\"addedSublistIds\":[],"
                                            + "\"newGroupname\":\"#{updated_name}\"," // Injects unique loop name
                                            + "\"tagId\":\"1\"," // Preserved from cURL specification
                                            + "\"groupType\":\"1\","
                                            + "\"removedMDNListIds\":[],"
                                            + "\"modifiedMembers\":[],"
                                            + "\"removedSublistIds\":[],"
                                            + "\"contactPairing\":\"false\"," // String boolean literal matching cURL
                                            + "\"overrideDND\":null"
                                            + "}"))
                                    .check(status().is(200)))

                            // Progress logger terminal block
                            .exec(session -> {
                                int progress = session.getInt("updateCounter") + 1;
                                System.out.println(">>> [MODIFY LOOP " + progress + "/10] Altered Target ID: " + session.getString("target_id") + " -> " + session.getString("updated_name"));
                                return session;
                            })
                            .pause(1)
            )
    );


    // --- 6. DELETE TALK GROUP WORKFLOW (Cleans up all 10 created items) ---
    private static final ChainBuilder deleteTalkGroup = group("Step 06: Delete Talk Group").on(
            // Loops dynamically over your 10 captured database IDs
            foreach("#{created_ids_list}", "target_id", "deleteCounter").on(
                    exec(http("14. API: Delete Group")
                            .post("/cat/rest/deleteGroup") // 🌟 Real matching endpoint path
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
                            // 🌟 REPLICATED DATA RAW SCHEMA: Matches your exact cURL parameters
                            .body(StringBody("{"
                                    + "\"contactPairing\":false,"
                                    + "\"tagId\":\"2\"," // Preserved from cURL specification
                                    + "\"groupId\":\"#{target_id}\"" // Targets each loop ID dynamically
                                    + "}"))
                            .check(status().is(200)))

                            // Progress logger terminal block
                            .exec(session -> {
                                int progress = session.getInt("deleteCounter") + 1;
                                System.out.println(">>> [DELETE LOOP " + progress + "/10] Destroyed Target ID: " + session.getString("target_id"));
                                return session;
                            })
                            .pause(1)
            )
    );



    private final ScenarioBuilder scn = scenario("Scenario2_Login_TalkGroupManagement")
            .feed(userFeeder)
            .exec(loginGroup)
            .exec(baselinePageLoad)
            .exec(createTalkGroup)  // 1. Spuns up 10 items & tracks IDs
            .exec(listTalkGroups)   // 2. Lists them
            .exec(updateTalkGroup)  // 3. Modifies those exact 10 items sequentially
            .exec(deleteTalkGroup); // 4. Drops those exact 10 items sequentially


    {
        setUp(scn.injectOpen(atOnceUsers(1))).protocols(httpProtocol);
    }
}