package motorola;

import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/*
    * This Gatling Simulation script is designed
    * to automate the creation of admin users in the WMS application.
    *
    *
    * CreateUsersSimulation.csv contains super admin credentials followed by the new user details to be created.
    * The script performs the following steps:
    * Logins to Keycloak using the super admin credentials to obtain necessary authentication cookies.
    * Initializes the application baseline by calling essential APIs to set up the session context.
    *
    * set up loop which is present at the end of file
    * will be used to create the number of desired users by adjusting the rampUsers count and duration.
 */

public class CreateUsersSimulation extends Simulation {

    // 1. Feeder referencing your verified CSV dataset
    private final FeederBuilder<String> feeder = csv("CreateUsersSimulation.csv").queue();

    // 2. Base HTTP Options mapped exactly from your working Scenario 7 script
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

    // 3. Sequential Scenario Workflow Chain
    private ScenarioBuilder scn = scenario("Create Admin Users Flow")
            .feed(feeder)

            // --- STEP 01: KEYCLOAK LOGIN HANDSHAKE ---
            .exec(http("1. Initial Redirect")
                    .get("/cat/view/idmlogin")
                    .disableFollowRedirect()
                    .check(status().is(302), header("Location").saveAs("keycloakUrl")))
            .exitHereIfFailed()

            .exec(http("2. Load Keycloak Page")
                    .get(session -> session.getString("keycloakUrl"))
                    .check(status().is(200), regex("var loginAction = '([^']*)'").saveAs("rawLoginAction")))
            .exitHereIfFailed()

            .exec(session -> {
                String raw = session.getString("rawLoginAction");
                return session.set("loginAction", raw != null ? raw.replace("&amp;", "&") : "");
            })

            .exec(http("3. Submit Credentials")
                    .post(session -> session.getString("loginAction"))
                    .formParam("username", "#{superadminusername}")
                    .formParam("password", "#{superadminpassword}")
                    .formParam("login", "log in")
                    .disableFollowRedirect()
                    .check(status().is(302), header("Location").saveAs("callbackUrl")))
            .exitHereIfFailed()

            .exec(http("3b. Keycloak Callback Handshake")
                    .get(session -> session.getString("callbackUrl"))
                    .check(status().is(200)))
            .exitHereIfFailed()

            // --- STEP 02: APPLICATION BASELINE INITIALIZATION ---
            // Gatling natively forwards all background infrastructure cookies automatically here!
            .exec(http("4. API: Get Global Data")
                    .post("/cat/rest/getGlobalData")
                    .check(status().is(200)))

            .exec(http("5. API: Refresh Token")
                    .post("/cat/view/refreshToken")
                    .body(StringBody("{}"))
                    .check(status().is(200)))
            .pause(1)

            // --- STEP 03: TARGET USER PROVISIONING ---
            .exec(http("Create User Request")
                    .post("/cat/rest/createUser")
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                    .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com/cat/static/cobalt-ngcatui/index.html")
                    .body(StringBody("{"
                            + "\"userid\":\"#{username}\","
                            + "\"pwd\":\"#{password}\","
                            + "\"generatepwd\":false,"
                            + "\"email\":\"#{email}\","
                            + "\"roles\":[\"Organization Admin\"],"
                            + "\"attributes\":{\"corpid\":\"AshwinCorp\",\"actions\":[\"1\"]},"
                            + "\"userRoleId\":\"0881af1f-fd6a-4115-8e5b-4568c54d685b\","
                            + "\"appId\":\"1\","
                            + "\"roleId\":\"21\""
                            + "}"))
                    .check(status().is(200))
                    .check(bodyString().saveAs("lastResponse"))
            )
            .exec(session -> {
                System.out.println("DEBUG >>> Creation Response: " + session.getString("lastResponse"));
                return session;
            });

    {
        setUp(
                // --- FIX: Staggers 6 users across 12 seconds (1 user every 2 seconds) to bypass brute-force protection
                scn.injectOpen(rampUsers(9).during(12))
        ).protocols(httpProtocol);
    }
}
