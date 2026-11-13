# Payara Community Tools for IntelliJ IDEA

[![License](https://img.shields.io/badge/License-EPL%202.0-red.svg)](http://www.eclipse.org/legal/epl-2.0)

### Payara Server with IntelliJ IDEA Ultimate

Enable **Jakarta EE: Application Servers** and create a **Payara Server
(Ultimate) > Local** run configuration. Under **Application server > Configure**,
add a **Payara Server** installation. Select the server JRE and domain on the
**Server** tab; Payara 5 can use Java 8 independently of the plugin's Java 25
build toolchain. Administration credentials use IntelliJ's password storage;
leaving the password empty uses the server default or a saved `asadmin login`.

The native **Deployment** tab accepts project artifacts, external EAR/WAR files
and exploded applications. Its optional **Application name** field keeps the
same server deployment when the archive version changes. Use **Build Artifacts**
in **Before launch** for project artifacts; external archives must already exist.
The EAR/WAR descriptors continue to supply the application's context roots.

**Run**, **Debug** and **Stop** use IntelliJ's application server lifecycle.
The server and its deployments appear in **Services**, with deploy, redeploy,
undeploy and status refresh actions. **Update Application** offers the native
update policies: redeploy for archives, resource updates for project exploded
artifacts and class HotSwap while debugging. Structural Java changes still
require redeployment when the selected JVM cannot HotSwap them.
JVM options entered in IntelliJ apply to the server JVM; the domain's startup
settings are read without modifying `domain.xml`. Stop another running instance
of the domain before launching it from the IDE.
Follow application logs in **Payara server.log**, enabled by default in **Logs**.
To avoid repeating them in **Server**, remove `java.util.logging.ConsoleHandler`
from `handlers` in the domain's `config/logging.properties`. Keep file logging
enabled; the **Server** console continues to show process diagnostics.
Domains with a custom master password must have that password saved by Payara;
the default domain master password also works.

This integration is loaded only when the Ultimate application server plugin is
available. Existing **Payara Server > Local** and **Maven** profiles remain usable.

### Local Payara Server

Create a **Payara Server > Local** run configuration. On the **Server** tab,
select the existing Payara installation, domain folder name and server JDK
(for example, Java 8 for Payara 5). The plugin's Java 25 build toolchain does
not change the server JDK.

The separate **Artifacts** tab accepts multiple IntelliJ project artifacts,
external EAR/WAR archives and exploded applications. Project artifacts are
added to IntelliJ's build-before-launch task; external files must already be
built. Deployments run in the listed order after the domain starts, using
the context roots in the application descriptors. A failed deployment stops
the remaining deployments and reports the error in the Run console.
Use **Edit application name** to keep a deployment name when replacing a
versioned archive; otherwise the archive filename supplies the name.

Stop an independently running copy of the domain before launching this
configuration. **Debug** uses the socket port in the domain's `java-config`
settings. **Stop** terminates the server and any deployment command started
by the run configuration. Protected administration endpoints use credentials
previously saved by `asadmin login` for `localhost` and the domain's admin port.
The existing Maven configuration remains available under **Payara Server > Maven**.

### Development

The plugin targets IntelliJ IDEA 2026.3 (builds `263.7307.200` through `263.*`).
The SDK and Plugin Verifier target are pinned to the stable `2026.3` release in
`gradle.properties`. IntelliJ IDEA 2025.3 and later use the unified distribution.

Install JDK 25 and select it as `JAVA_HOME` and the Gradle JVM in IntelliJ IDEA.
Use the included Gradle 9.9.0 wrapper:

```sh
./gradlew clean check buildPlugin verifyPlugin
./gradlew runIde
```

The installable ZIP is generated in `build/distributions/`. Compatibility reports
are written to `build/reports/pluginVerifier/`. To debug the sandbox IDE, run
`./gradlew runIde --debug-jvm` and attach a debugger to localhost port 5005.
Application run configurations select their own JDK independently of the plugin's
build toolchain.

The Jenkins agent requires a JDK installation named `zulu-25` and uses the wrapper
to run the same checks. SpotBugs findings are reported in
`build/reports/spotbugs/`; they retain the existing non-blocking policy.

### Ecosystem Plugin Support
Support for the Payara IntelliJ Community Tools plugin is handled in the [Ecosystem Support Repository](https://github.com/payara/ecosystem-support)

### IntelliJ IDEA Payara Plugin Documentation
Full documentation for the configuration and usage of the Payara IntelliJ Community Tools plugin can be found in the [technical documentation](https://docs.payara.fish/community/docs/Technical%20Documentation/Ecosystem/IDE%20Integration/IntelliJ%20Plugin/Overview.html)
