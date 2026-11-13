package fish.payara.server.ultimate;

import com.intellij.debugger.impl.GenericDebuggerRunnerSettings;
import com.intellij.debugger.settings.DebuggerSettings;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.javaee.appServers.run.localRun.CommandLineExecutableObject;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.RunManager;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.javaee.appServers.run.execution.J2EERunnableState;
import com.intellij.javaee.appServers.run.execution.update.RedeployArtifactsUpdatingPolicy;
import com.intellij.javaee.appServers.appServerIntegrations.AppServerIntegration;
import com.intellij.javaee.appServers.appServerIntegrations.ApplicationServer;
import com.intellij.javaee.appServers.deployment.DeploymentModel;
import com.intellij.javaee.appServers.deployment.DeploymentStatus;
import com.intellij.javaee.appServers.deployment.ExternalFileDeploymentSource;
import com.intellij.javaee.appServers.run.configuration.CommonStrategy;
import com.intellij.javaee.appServers.serverInstances.ApplicationServersManager;
import com.intellij.javaee.oss.admin.JavaeeAdminDeployCallback;
import com.intellij.javaee.oss.server.DeploymentStatusManager;
import com.intellij.javaee.oss.server.JavaeePersistentData;
import com.intellij.javaee.oss.server.JavaeeServerInstanceImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.options.SettingsEditorGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.packaging.artifacts.ArtifactType;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import org.jdom.Element;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static com.intellij.testFramework.junit5.fixture.FixturesKt.projectFixture;
import static org.junit.jupiter.api.Assertions.*;

@TestApplication
class PayaraUltimateTest {
    static final TestFixture<Project> project = projectFixture();
    @TempDir Path temp;
    private Sdk sdk;
    private ApplicationServer server;
    private CommonStrategy configuration;

    @BeforeEach
    void configureServer() throws Exception {
        Path glassfish = temp.resolve("glassfish");
        Files.createDirectories(glassfish.resolve("bin"));
        Files.createDirectories(glassfish.resolve("lib/client"));
        Path config = Files.createDirectories(glassfish.resolve("domains/local/config"));
        Files.writeString(config.resolve("domain.xml"), """
                <domain><servers><server name="server" config-ref="local-config"/></servers>
                <configs><config name="local-config">
                <java-config debug-options="-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=9010"/>
                <network-config><network-listeners>
                  <network-listener name="admin-listener" port="5959"/>
                  <network-listener name="http-listener-1" port="8181"/>
                </network-listeners></network-config></config></configs></domain>
                """);
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeNativeAsadmin.class.getName());
        String classFile = FakeNativeAsadmin.class.getName().replace('.', '/') + ".class";
        try (var jar = new JarOutputStream(Files.newOutputStream(glassfish.resolve("lib/client/appserver-cli.jar")), manifest);
             var input = getClass().getResourceAsStream("/" + classFile)) {
            assertNotNull(input);
            jar.putNextEntry(new JarEntry(classFile));
            input.transferTo(jar);
            jar.closeEntry();
        }
        Files.createDirectories(glassfish.resolve("modules"));
        var kernelManifest = new Manifest();
        kernelManifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        kernelManifest.getMainAttributes().putValue("Bundle-Version", "5.2021.4");
        try (var ignored = new JarOutputStream(Files.newOutputStream(glassfish.resolve("modules/kernel.jar")), kernelManifest)) { }

        sdk = JavaSdk.getInstance().createJdk("Payara Ultimate " + temp.getFileName(), System.getProperty("java.home"), false);
        ApplicationManager.getApplication().invokeAndWait(() -> WriteAction.run(() -> {
            ProjectJdkTable.getInstance().addJdk(sdk);
            var integration = AppServerIntegration.EXTENSION_POINT.findExtensionOrFail(PayaraServerIntegration.class);
            var data = new JavaeePersistentData();
            data.HOME = temp.toString();
            var servers = ApplicationServersManager.getInstance().createModifiableModel();
            try {
                server = servers.createNewApplicationServer("Payara test " + temp.getFileName(), new java.io.File[]{glassfish.resolve("modules/kernel.jar").toFile()}, data);
                server.setIntegration(integration);
                servers.commit();
            } catch (RuntimeException | Error e) { servers.dispose(); throw e; }
            var type = new PayaraUltimateConfigurationType();
            configuration = (CommonStrategy) type.getLocalFactory().createTemplateConfiguration(project.get());
            configuration.setApplicationServer(server);
            configuration.setAlternativeJreEnabled(true);
            configuration.setAlternativeJrePath(sdk.getName());
            ((PayaraServerModel) configuration.getServerModel()).DOMAIN = "local";
        }));
    }

    @AfterEach
    void removeServer() {
        ApplicationManager.getApplication().invokeAndWait(() -> WriteAction.run(() -> {
            if (server != null) {
                var servers = ApplicationServersManager.getInstance().createModifiableModel();
                servers.deleteApplicationServer(server);
                servers.commit();
            }
            if (sdk != null) ProjectJdkTable.getInstance().removeJdk(sdk);
        }));
    }

    @Test
    void providesNativeServerAndDeploymentEditorsAndPersistsStableDeploymentNames() throws Exception {
        assertEquals("5.2021.4", ((PayaraServerIntegration) server.getSourceIntegration()).detectVersion((JavaeePersistentData) server.getPersistentData()));
        assertEquals(5959, ((PayaraServerModel) configuration.getServerModel()).getServerPort());
        assertEquals(8181, configuration.getServerModel().getLocalPort());
        var deployment = deployment("api-2026-11-13.ear", "stable-api");
        var xml = new Element("configuration");
        configuration.writePersistent(xml);
        var restored = configuration.clone();
        restored.readPersistent(xml);
        assertEquals("stable-api", ((PayaraDeploymentModel) restored.getDeploymentModels().iterator().next()).getApplicationName());
        assertEquals("local", ((PayaraServerModel) restored.getServerModel()).DOMAIN);
        ApplicationManager.getApplication().invokeAndWait(() -> {
            var editor = (SettingsEditorGroup<?>) configuration.getConfigurationEditor();
            try {
                var names = editor.getEditors().stream().map(tab -> tab.first).toList();
                assertTrue(names.contains("Server"), names.toString());
                assertTrue(names.contains("Deployment"), names.toString());
                for (var tab : editor.getEditors()) assertNotNull(tab.second.getComponent());
            } finally { Disposer.dispose(editor); }
        });
        assertEquals("stable-api", deployment.deploymentName(temp.resolve("missing.ear").toFile()));
        var model = (PayaraServerModel) configuration.getServerModel();
        model.setPassword("never-in-shared-xml");
        var shared = new Element("server");
        model.writePersistent(shared);
        assertFalse(com.intellij.openapi.util.JDOMUtil.writeElement(shared).contains("never-in-shared-xml"));
    }

    @Test
    void startsAndStopsThroughAsadminWithoutStartingTheDomainJvmInTheConsole() throws Exception {
        var policy = new PayaraStartupPolicy();
        var model = (PayaraServerModel) configuration.getServerModel();
        Map<String, String> environment = new HashMap<>();
        for (var variable : policy.getEnvironmentVariables(model)) environment.put(variable.NAME, variable.VALUE);
        assertEquals(sdk.getHomePath(), environment.get("JAVA_HOME"));
        assertEquals(sdk.getHomePath(), environment.get("AS_JAVA"));
        for (boolean debug : List.of(false, true)) {
            var script = (CommandLineExecutableObject) policy.getDefaultStartupScript(model, debug);
            var arguments = List.of(script.getParameters());
            assertTrue(arguments.getFirst().startsWith(sdk.getHomePath()));
            assertTrue(arguments.contains("start-domain"));
            assertTrue(arguments.contains("--debug=" + debug));
            assertFalse(arguments.stream().anyMatch(p -> p.startsWith("--verbose") || p.startsWith("--dry-run") || p.contains("jdwp")));
            assertTrue(((PayaraServerIntegration) configuration.getIntegration()).isStartupScriptTerminating(script));
            assertTrue(runScript(script, environment).contains("Command start-domain executed successfully."));
        }
        var stop = (CommandLineExecutableObject) policy.getDefaultShutdownScript(model, false);
        assertTrue(List.of(stop.getParameters()).contains("stop-domain"));
        assertTrue(runScript(stop, environment).contains("Command stop-domain executed successfully."));
        var calls = Files.readAllLines(temp.resolve("glassfish/bin/calls.txt"));
        assertEquals(3, calls.size());
        assertTrue(calls.getLast().contains("stop-domain\t--domaindir\t" + temp.resolve("glassfish/domains") + "\tlocal"));
    }

    @Test
    void configuresTheDebuggerFromTheDomainWithoutDebuggingAsadmin() throws Exception {
        var policy = new PayaraStartupPolicy();
        var model = (PayaraServerModel) configuration.getServerModel();
        var settings = new GenericDebuggerRunnerSettings();
        settings.setTransport(DebuggerSettings.SHMEM_TRANSPORT);
        policy.initSettings(model, settings);
        policy.checkSettings(model, settings);
        assertEquals("9010", settings.getDebugPort());
        assertFalse(settings.isRemote());
        assertEquals(DebuggerSettings.SOCKET_TRANSPORT, settings.getTransport());
        var domain = temp.resolve("glassfish/domains/local/config/domain.xml");
        Files.writeString(domain, Files.readString(domain).replace("server=n", "server=y"));
        assertTrue(assertThrows(Exception.class, () -> policy.checkSettings(model, settings)).getMessage().contains("server=n"));
        configuration.getSettingsBean().COMMON_VM_ARGUMENTS = "-Xmx1g";
        assertTrue(assertThrows(Exception.class, model::checkConfiguration).getMessage().contains("domain.xml"));
    }

    @Test
    void deploysRedeploysRefreshesAndUndeploysWhileKeepingCredentialsPrivate() throws Exception {
        var deployment = deployment("api-2026-11-13.ear", "stable-api");
        var model = (PayaraServerModel) configuration.getServerModel();
        var instance = new JavaeeServerInstanceImpl(configuration, new DeploymentStatusManager());
        var admin = new PayaraAdmin(model, instance);
        admin.start("localhost", 5959, "admin", "sëcret=#\\\nline", null);
        var state = new AtomicReference<DeploymentStatus>();
        var errors = new ArrayList<Exception>();
        var callback = callback(state, errors);
        try {
            assertTrue(admin.doConnect());
            admin.startDeploy(deployment, deployment.getDeploymentSource().getFile(), callback);
            assertEquals(DeploymentStatus.DEPLOYED, state.get(), errors.toString());
            admin.startDeploy(deployment, deployment.getDeploymentSource().getFile(), callback);
            assertEquals(List.of("stable-api"), Files.readAllLines(temp.resolve("glassfish/bin/deployed.txt")));
            admin.startUpdateDeploymentStatus(deployment, deployment.getDeploymentSource().getFile(), callback);
            assertEquals(DeploymentStatus.DEPLOYED, state.get());
            admin.startUndeploy(deployment, deployment.getDeploymentSource().getFile(), callback);
            assertEquals(DeploymentStatus.NOT_DEPLOYED, state.get());
            admin.startUpdateDeploymentStatus(deployment, deployment.getDeploymentSource().getFile(), callback);
            assertEquals(DeploymentStatus.NOT_DEPLOYED, state.get());
            assertTrue(errors.isEmpty(), errors.toString());
            var failed = deployment("failed.ear", "fail");
            admin.startDeploy(failed, failed.getDeploymentSource().getFile(), callback);
            assertEquals(DeploymentStatus.FAILED, state.get());
            assertEquals(1, errors.size());
            var calls = Files.readString(temp.resolve("glassfish/bin/calls.txt"));
            assertFalse(calls.contains("sëcret"));
            for (String call : calls.lines().toList()) {
                var arguments = List.of(call.split("\t"));
                int file = arguments.indexOf("--passwordfile");
                if (file >= 0) assertFalse(Files.exists(Path.of(arguments.get(file + 1))));
            }
        } finally { admin.shutdown(); }
    }

    @Test
    void rejectsDuplicateDeploymentNamesAndInvalidDomains() throws Exception {
        deployment("api-1.ear", "same-name");
        deployment("api-2.ear", "same-name");
        var error = assertThrows(Exception.class, () -> configuration.getServerModel().checkConfiguration());
        assertTrue(error.getMessage().contains("Duplicate"), error.toString());
        ((PayaraServerModel) configuration.getServerModel()).DOMAIN = "../other";
        assertTrue(assertThrows(Exception.class, () -> configuration.getServerModel().checkConfiguration()).getMessage().contains("domain name"));
        ((PayaraServerModel) configuration.getServerModel()).DOMAIN = "local";
        ApplicationManager.getApplication().invokeAndWait(() -> WriteAction.run(() -> {
            var modificator = sdk.getSdkModificator();
            modificator.setHomePath(temp.resolve("missing-jdk").toString());
            modificator.commitChanges();
        }));
        assertTrue(assertThrows(Exception.class, () -> configuration.getServerModel().checkConfiguration()).getMessage().contains("Java SDK"));
    }

    @Test
    void usesTheNativeRunAndDebugRunnersAndEnablesRedeploymentInOrder() throws Exception {
        deployment("api.ear", "api");
        assertTrue(configuration.getDeploymentProvider().isDeployOrderMatter());
        var types = configuration.getDeploymentProvider().getSupportedArtifactTypes().stream().map(ArtifactType::getId).toList();
        assertTrue(types.containsAll(List.of("ear", "war", "exploded-ear", "exploded-war")), types.toString());
        assertFalse(types.contains("jar"));
        assertTrue(configuration.getDeploymentProvider().isResourcesReloadingSupported(configuration, ArtifactType.findById("exploded-war")));
        assertTrue(new RedeployArtifactsUpdatingPolicy().isAvailableForConfiguration(configuration));
        var saved = RunManager.getInstance(project.get()).createConfiguration(configuration, configuration.getFactory());
        for (var executor : List.of(DefaultRunExecutor.getRunExecutorInstance(), DefaultDebugExecutor.getDebugExecutorInstance())) {
            var environment = ExecutionEnvironmentBuilder.create(executor, saved).build();
            assertTrue(environment.getRunner().canRun(executor.getId(), configuration));
            configuration.checkRunnerSettings(environment.getRunner(), environment.getRunnerSettings(), environment.getConfigurationSettings());
            assertInstanceOf(J2EERunnableState.class, configuration.getState(executor, environment));
            if (DefaultDebugExecutor.EXECUTOR_ID.equals(executor.getId())) {
                var debugging = assertInstanceOf(GenericDebuggerRunnerSettings.class, environment.getRunnerSettings());
                assertEquals("9010", debugging.getDebugPort());
                assertFalse(debugging.isRemote());
            }
        }
    }

    private PayaraDeploymentModel deployment(String file, String name) throws Exception {
        Path archive = Files.write(temp.resolve(file), new byte[0]);
        var deployment = (PayaraDeploymentModel) configuration.getDeploymentSettings()
                .getOrCreateModel(new ExternalFileDeploymentSource(archive.toFile()));
        deployment.setApplicationName(name);
        return deployment;
    }

    private static String runScript(CommandLineExecutableObject script, Map<String, String> environment) throws Exception {
        var process = script.createProcessHandler(null, environment);
        var output = new CopyOnWriteArrayList<String>();
        process.addProcessListener(new ProcessAdapter() {
            @Override public void onTextAvailable(ProcessEvent event, Key type) { output.add(event.getText()); }
        });
        process.startNotify();
        try {
            assertTrue(process.waitFor(10_000));
            assertEquals(0, process.getExitCode());
            return String.join("", output);
        } finally {
            if (!process.isProcessTerminated()) process.destroyProcess();
        }
    }

    private static JavaeeAdminDeployCallback callback(AtomicReference<DeploymentStatus> state, List<Exception> errors) {
        return new JavaeeAdminDeployCallback() {
            @Override public void setDeploymentStatus(DeploymentModel model, DeploymentStatus status) { state.set(status); }
            @Override public void setDeploymentStatus(DeploymentModel model, DeploymentStatus status, String message) { state.set(status); }
            @Override public void setDeploymentStatus(DeploymentModel model, DeploymentStatus status, Exception error) { state.set(status); errors.add(error); }
            @Override public void showDeploymentMessage(DeploymentModel model, String message) { }
        };
    }
}
