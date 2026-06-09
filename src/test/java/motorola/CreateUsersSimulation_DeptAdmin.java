package motorola;

import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/*
 * This Gatling Simulation script is designed
 * to automate the creation of Department Admin users in the WMS application.
 *
 * CreateUsersSimulation_OrgAdmin.csv contains super admin credentials followed by the new user details to be created.
 */

public class CreateUsersSimulation_DeptAdmin extends Simulation {

    // 1. Feeder referencing your verified CSV dataset
    private final FeederBuilder<String> feeder = csv("CreateUsersSimulation_DeptAdmin.csv").queue();

    // 2. Base HTTP Options
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
    private ScenarioBuilder scn = scenario("Create Department Admin Users Flow")
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
            .exec(http("4. API: Get Global Data")
                    .post("/cat/rest/getGlobalData")
                    .check(status().is(200)))

            .exec(http("5. API: Refresh Token")
                    .post("/cat/view/refreshToken")
                    .body(StringBody("{}"))
                    .check(status().is(200)))
            .pause(1)

            // --- STEP 03: TARGET DEPARTMENT USER PROVISIONING ---
            .exec(http("Create Dept Admin Request")
                    .post("/cat/rest/createUser")
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://wms-dev-xdmauto.msiidcitgcloud.com")
                    .header("Referer", "https://wms-dev-xdmauto.msiidcitgcloud.com/cat/static/cobalt-ngcatui/index.html")
                    .body(StringBody("{"
                            + "\"userid\":\"#{username}\","
                            + "\"pwd\":\"#{password}\","
                            + "\"generatepwd\":false,"
                            + "\"email\":\"#{email}\","
                            + "\"roles\":[\"Department Admin\"],"
                            + "\"attributes\":{\"corpid\":\"AshwinCorp\",\"actions\":[\"1\"]},"
                            + "\"userRoleId\":\"4273c565-96d7-4c49-97d3-451c90273e69\","
                            + "\"appId\":\"1\","
                            + "\"roleId\":\"21\""
                            + "}"))
                    .check(status().is(200))
                    .check(bodyString().saveAs("lastResponse"))
            )
            .exec(session -> {
                System.out.println("DEBUG >>> Dept Admin Creation Response: " + session.getString("lastResponse"));
                return session;
            });

    {
        setUp(
                scn.injectOpen(rampUsers(1).during(10))
        ).protocols(httpProtocol);
    }
}
