package fish.payara.server.local;

import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.util.Key;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static com.intellij.testFramework.junit5.fixture.FixturesKt.projectFixture;
import static org.junit.jupiter.api.Assertions.*;

@TestApplication
class ServerLocalRunStateTest {
    static final TestFixture<Project> project = projectFixture();
    @TempDir Path temp;

    @Test
    void startsOneServerDeploysAllArtifactsInOrderAndStopsTheProcess() throws Exception {
        run(List.of("api", "ocean", "job"), "All Payara artifacts deployed.", 3);
    }

    @Test
    void reportsDeploymentFailureAndSkipsLaterArtifacts() throws Exception {
        run(List.of("api", "fail", "job"), "Remaining deployments were skipped.", 2);
    }

    @Test
    void attachesTheDebuggerToTheDomainPortWithoutDebuggingAsadmin() throws Exception {
        ServerLocalConfigurationTest.domain(temp);
        var configuration = ServerLocalConfigurationTest.configuration(project.get(), temp);
        var sdk = JavaSdk.getInstance().createJdk("Payara debug test " + temp.getFileName(), System.getProperty("java.home"), false);
        ApplicationManager.getApplication().invokeAndWait(() -> WriteAction.run(() -> ProjectJdkTable.getInstance().addJdk(sdk)));
        try {
            configuration.setJdkName(sdk.getName());
            var environment = ExecutionEnvironmentBuilder.create(project.get(), DefaultDebugExecutor.getDebugExecutorInstance(), configuration).build();
            var state = new ServerLocalRunState(environment, configuration);
            var parameters = state.getJavaParameters();
            assertEquals(sdk.getHomePath(), parameters.getEnv().get("JAVA_HOME"));
            assertEquals(sdk.getHomePath(), parameters.getEnv().get("AS_JAVA"));
            assertTrue(parameters.getProgramParametersList().getParameters().contains("--debug=true"));
            assertTrue(parameters.getVMParametersList().getParameters().stream().noneMatch(p -> p.contains("jdwp")));
            var connection = state.createRemoteConnection(environment);
            assertNotNull(connection);
            assertEquals("9010", connection.getApplicationAddress());
            assertFalse(connection.isServerMode());
            assertTrue(state.isPollConnection());
        } finally {
            ApplicationManager.getApplication().invokeAndWait(() -> WriteAction.run(() -> ProjectJdkTable.getInstance().removeJdk(sdk)));
        }
    }

    private void run(List<String> names, String expectedMessage, int deployed) throws Exception {
        ServerLocalConfigurationTest.domain(temp);
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeAsadmin.class.getName());
        String classFile = FakeAsadmin.class.getName().replace('.', '/') + ".class";
        try (var jar = new JarOutputStream(Files.newOutputStream(temp.resolve("glassfish/lib/client/appserver-cli.jar")), manifest);
             var input = getClass().getResourceAsStream("/" + classFile)) {
            assertNotNull(input);
            jar.putNextEntry(new JarEntry(classFile));
            input.transferTo(jar);
            jar.closeEntry();
        }
        var configuration = ServerLocalConfigurationTest.configuration(project.get(), temp);
        var selected = new ArrayList<ServerLocalConfiguration.Deployment>();
        for (String name : names) {
            Path archive = Files.write(temp.resolve(name + ".ear"), new byte[0]);
            selected.add(new ServerLocalConfiguration.Deployment("", archive.toString()));
        }
        configuration.setDeployments(selected);
        var sdk = JavaSdk.getInstance().createJdk("Payara process test " + temp.getFileName(), System.getProperty("java.home"), false);
        ApplicationManager.getApplication().invokeAndWait(() -> WriteAction.run(() -> ProjectJdkTable.getInstance().addJdk(sdk)));
        try {
            configuration.setJdkName(sdk.getName());
            var environment = ExecutionEnvironmentBuilder.create(project.get(), DefaultRunExecutor.getRunExecutorInstance(), configuration).build();
            var state = new ServerLocalRunState(environment, configuration);
            configuration.setServerHome(temp.resolve("edited-after-launch").toString());
            var handler = state.startProcess();
            var output = new CopyOnWriteArrayList<String>();
            handler.addProcessListener(new ProcessAdapter() {
                @Override public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key type) { output.add(event.getText()); }
            });
            try {
                handler.startNotify();
                assertTimeoutPreemptively(Duration.ofSeconds(25), () -> {
                    while (!String.join("", output).contains(expectedMessage)) Thread.sleep(25);
                });
                List<String> calls = Files.readAllLines(temp.resolve("glassfish/bin/calls.txt"));
                assertEquals(deployed + 1, calls.size());
                assertTrue(calls.getFirst().contains("start-domain\t--verbose=true"));
                for (int i = 0; i < deployed; i++) {
                    assertTrue(calls.get(i + 1).contains("--port\t5959\tdeploy\t--force=true\t--name\t" + names.get(i)));
                }
            } finally {
                handler.destroyProcess();
                assertTrue(handler.waitFor(10_000));
            }
        } finally {
            ApplicationManager.getApplication().invokeAndWait(() -> WriteAction.run(() -> ProjectJdkTable.getInstance().removeJdk(sdk)));
        }
    }
}
