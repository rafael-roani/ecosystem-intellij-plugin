package fish.payara.server.local;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.options.SettingsEditorGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.Disposer;
import com.intellij.execution.RunManagerEx;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.packaging.elements.PackagingElementFactory;
import com.intellij.packaging.impl.artifacts.PlainArtifactType;
import com.intellij.packaging.impl.run.BuildArtifactsBeforeRunTaskProvider;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import fish.payara.server.PayaraServerConfigurationType;
import org.jdom.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.intellij.testFramework.junit5.fixture.FixturesKt.projectFixture;
import static org.junit.jupiter.api.Assertions.*;

@TestApplication
class ServerLocalConfigurationTest {
    static final TestFixture<Project> project = projectFixture();
    @TempDir Path temp;

    static ServerLocalConfiguration configuration(Project project, Path home) {
        var configuration = new ServerLocalConfiguration(project,
                new ServerLocalConfigurationFactory(new PayaraServerConfigurationType()), "Local Payara");
        configuration.setServerHome(home.toString());
        configuration.setDomainName("local");
        return configuration;
    }

    static void domain(Path home) throws Exception {
        Files.createDirectories(home.resolve("glassfish/lib/client"));
        Files.createDirectories(home.resolve("glassfish/bin"));
        Files.write(home.resolve("glassfish/lib/client/appserver-cli.jar"), new byte[0]);
        Path domain = home.resolve("glassfish/domains/local/config");
        Files.createDirectories(domain);
        Files.writeString(domain.resolve("domain.xml"), """
                <domain>
                  <system-property name="ADMIN_PORT" value="4949"/>
                  <servers><server name="server" config-ref="local-config">
                    <system-property name="ADMIN_PORT" value="5959"/>
                  </server></servers>
                  <configs><config name="local-config">
                    <java-config debug-options="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:9010"/>
                    <network-config><network-listeners>
                      <network-listener name="admin-listener" port="${ADMIN_PORT}"/>
                    </network-listeners></network-config>
                  </config></configs>
                </domain>
                """);
    }

    @Test
    void persistsArtifactsAndFilesInOrderWithoutSharingClonedLists() throws Exception {
        var original = configuration(project.get(), temp);
        var selected = List.of(new ServerLocalConfiguration.Deployment("API EAR", "", "stable-api"),
                new ServerLocalConfiguration.Deployment("", temp.resolve("ocean.ear").toString()));
        original.setJdkName("Java 8");
        original.setDeployments(selected);
        Element xml = new Element("configuration");
        original.writeExternal(xml);
        var restored = configuration(project.get(), temp.resolve("other"));
        restored.readExternal(xml);
        assertEquals(original.getServerHome(), restored.getServerHome());
        assertEquals("local", restored.getDomainName());
        assertEquals("Java 8", restored.getJdkName());
        assertEquals(selected, restored.getDeployments());
        restored.clone().setDeployments(List.of());
        assertEquals(selected, restored.getDeployments());
    }

    @Test
    void hasAnArtifactsTabSeparateFromServerSettings() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            var configuration = configuration(project.get(), temp);
            configuration.setDeployments(List.of(new ServerLocalConfiguration.Deployment("", "/tmp/api.ear")));
            var editor = (SettingsEditorGroup<ServerLocalConfiguration>) configuration.getConfigurationEditor();
            try {
                assertEquals(List.of("Server", "Artifacts"), editor.getEditors().stream().map(p -> p.first).toList());
                for (var tab : editor.getEditors()) {
                    assertNotNull(tab.second.getComponent());
                    tab.second.resetFrom(configuration);
                    assertDoesNotThrow(() -> tab.second.applyTo(configuration));
                }
                assertEquals(List.of(new ServerLocalConfiguration.Deployment("", "/tmp/api.ear")), configuration.getDeployments());
            } finally {
                Disposer.dispose(editor);
            }
        });
    }

    @Test
    void resolvesProjectArtifactsAndPreservesCommandArgumentsWithSpaces() throws Exception {
        domain(temp);
        Path output = Files.createDirectories(temp.resolve("build output"));
        Files.write(output.resolve("ocean api.ear"), new byte[0]);
        String name = "API " + temp.getFileName();
        ApplicationManager.getApplication().invokeAndWait(() -> WriteAction.run(() -> {
            var model = ArtifactManager.getInstance(project.get()).createModifiableModel();
            var artifact = model.addArtifact(name, PlainArtifactType.getInstance(), PackagingElementFactory.getInstance().createArchive("ocean api.ear"));
            artifact.setOutputPath(output.toString());
            model.commit();
        }));
        var configuration = configuration(project.get(), temp);
        Path versioned = Files.write(output.resolve("api-2026-11-12.ear"), new byte[0]);
        configuration.setDeployments(List.of(new ServerLocalConfiguration.Deployment(name, ""),
                new ServerLocalConfiguration.Deployment("", versioned.toString(), "stable-api")));
        assertEquals(List.of(List.of("deploy", "--force=true", "--name", "ocean api", output.resolve("ocean api.ear").toString()),
                List.of("deploy", "--force=true", "--name", "stable-api", versioned.toString())), configuration.createDeploymentCommands());
        assertEquals("5959", configuration.getAdminPort());
        assertEquals("9010", configuration.getDebugPort());
        assertTrue(configuration.isDebugServerListening());
        ApplicationManager.getApplication().invokeAndWait(() -> {
            var editor = new ServerArtifactsEditor(project.get());
            try {
                editor.resetFrom(configuration);
                assertDoesNotThrow(() -> editor.applyTo(configuration));
                var tasks = RunManagerEx.getInstanceEx(project.get()).getBeforeRunTasks(configuration, BuildArtifactsBeforeRunTaskProvider.ID);
                assertEquals(1, tasks.size());
                assertTrue(tasks.getFirst().isEnabled());
                assertEquals(1, tasks.getFirst().getItemsCount());
                var cleared = configuration.clone();
                cleared.setDeployments(List.of());
                editor.resetFrom(cleared);
                assertDoesNotThrow(() -> editor.applyTo(configuration));
                assertEquals(0, tasks.getFirst().getItemsCount());
                assertFalse(tasks.getFirst().isEnabled());
            } finally {
                Disposer.dispose(editor);
            }
        });
    }

    @Test
    void rejectsMissingOrDuplicateDeploymentsBeforeStarting() throws Exception {
        domain(temp);
        var configuration = configuration(project.get(), temp);
        Sdk sdk = JavaSdk.getInstance().createJdk("Payara test " + temp.getFileName(), System.getProperty("java.home"), false);
        ApplicationManager.getApplication().invokeAndWait(() -> WriteAction.run(() -> ProjectJdkTable.getInstance().addJdk(sdk)));
        try {
            configuration.setJdkName(sdk.getName());
            var missing = new ServerLocalConfiguration.Deployment("", temp.resolve("missing.ear").toString());
            configuration.setDeployments(List.of(missing));
            assertThrows(Exception.class, configuration::createDeploymentCommands);
            configuration.setDeployments(List.of(missing, missing));
            assertTrue(assertThrows(Exception.class, configuration::checkConfiguration).getMessage().contains("Duplicate"));
            configuration.setDeployments(List.of(new ServerLocalConfiguration.Deployment("Not an artifact", "")));
            assertTrue(assertThrows(Exception.class, configuration::checkConfiguration).getMessage().contains("Artifact not found"));
            configuration.setDeployments(List.of());
            for (String invalidDomain : List.of("../another", "--upgrade=true")) {
                configuration.setDomainName(invalidDomain);
                assertTrue(assertThrows(Exception.class, configuration::checkConfiguration).getMessage().contains("domain name"));
            }
        } finally {
            ApplicationManager.getApplication().invokeAndWait(() -> WriteAction.run(() -> ProjectJdkTable.getInstance().removeJdk(sdk)));
        }
    }
}
