/*
 * Copyright (c) 2026 Payara Foundation and/or its affiliates and others.
 * All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */
package fish.payara.server.local;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.components.PathMacroManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.options.SettingsEditorGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdkType;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class ServerLocalConfiguration extends RunConfigurationBase<RunConfigurationOptions> {
    private String serverHome = "";
    private String domainName = "domain1";
    private String jdkName = "";
    private List<Deployment> deployments = new ArrayList<>();

    public record Deployment(String artifactName, String filePath, String applicationName) {
        public Deployment(String artifactName, String filePath) {
            this(artifactName, filePath, "");
        }

        @Override
        public String toString() {
            String source = artifactName.isEmpty() ? filePath : artifactName;
            return applicationName.isEmpty() ? source : applicationName + " — " + source;
        }
    }

    public ServerLocalConfiguration(Project project, ConfigurationFactory factory, String name) {
        super(project, factory, name);
        Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (sdk != null) jdkName = sdk.getName();
    }

    public String getServerHome() { return serverHome; }
    public void setServerHome(String value) { serverHome = value; }
    public String getDomainName() { return domainName; }
    public void setDomainName(String value) { domainName = value; }
    public String getJdkName() { return jdkName; }
    public void setJdkName(String value) { jdkName = value; }
    public List<Deployment> getDeployments() { return List.copyOf(deployments); }
    public void setDeployments(List<Deployment> value) { deployments = new ArrayList<>(value); }

    public Path getGlassfishHome() {
        return glassfishHome(serverHome);
    }

    public static Path glassfishHome(String serverHome) {
        Path home = Path.of(serverHome);
        return Files.isRegularFile(home.resolve("lib/client/appserver-cli.jar")) ? home : home.resolve("glassfish");
    }

    public Path getDomainDirectory() {
        return getGlassfishHome().resolve("domains").resolve(domainName);
    }

    public Sdk getJdk() {
        return ProjectJdkTable.getInstance().findJdk(jdkName);
    }

    public Path resolveDeployment(Deployment deployment) throws RuntimeConfigurationError {
        String output = deployment.filePath();
        if (!deployment.artifactName().isEmpty()) {
            output = ReadAction.compute(() -> {
                Artifact artifact = ArtifactManager.getInstance(getProject()).findArtifact(deployment.artifactName());
                if (artifact == null) throw new RuntimeConfigurationError("Artifact not found: " + deployment.artifactName());
                return artifact.getOutputFilePath();
            });
        }
        if (output == null || output.isBlank()) throw new RuntimeConfigurationError("The artifact has no output path.");
        output = PathMacroManager.getInstance(getProject()).expandPath(output);
        return Path.of(output).toAbsolutePath().normalize();
    }

    public static String applicationName(Path output) throws RuntimeConfigurationError {
        if (output.getFileName() == null) throw new RuntimeConfigurationError("Select an application artifact, not a filesystem root.");
        return validateApplicationName(output.getFileName().toString().replaceFirst("(?i)\\.(ear|war)$", ""));
    }

    public static String validateApplicationName(String name) throws RuntimeConfigurationError {
        if (name.isBlank() || name.startsWith("__") || name.startsWith("-")) throw new RuntimeConfigurationError("Invalid application name: " + name);
        return name;
    }

    private static String deploymentName(Deployment deployment, Path output) throws RuntimeConfigurationError {
        return deployment.applicationName().isEmpty() ? applicationName(output) : validateApplicationName(deployment.applicationName());
    }

    public void checkDomain() throws RuntimeConfigurationError {
        try {
            if (serverHome.isBlank() || !Files.isRegularFile(getGlassfishHome().resolve("lib/client/appserver-cli.jar"))) {
                throw new RuntimeConfigurationError("Select a Payara Server installation.");
            }
            if (domainName.isBlank() || domainName.startsWith("-") || domainName.equals(".") || domainName.equals("..") || domainName.contains("/") || domainName.contains("\\")) {
                throw new RuntimeConfigurationError("Enter a domain name, such as domain1.");
            }
            if (!Files.isRegularFile(getDomainDirectory().resolve("config/domain.xml"))) {
                throw new RuntimeConfigurationError("Domain not found: " + domainName);
            }
        } catch (InvalidPathException e) {
            throw new RuntimeConfigurationError("Invalid server path: " + e.getInput());
        }
    }

    public JavaParameters createAsadminParameters(Sdk sdk) {
        var parameters = new JavaParameters();
        parameters.setJdk(sdk);
        String javaHome = sdk.getHomePath();
        parameters.setEnv(Map.of("JAVA_HOME", javaHome, "AS_JAVA", javaHome));
        parameters.setPassParentEnvs(true);
        parameters.setCharset(StandardCharsets.UTF_8);
        parameters.setWorkingDirectory(getGlassfishHome().resolve("bin").toString());
        parameters.setJarPath(getGlassfishHome().resolve("lib/client/appserver-cli.jar").toString());
        parameters.getProgramParametersList().add("--interactive=false");
        return parameters;
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationError {
        checkDomain();
        try {
            Sdk sdk = getJdk();
            if (sdk == null || !(sdk.getSdkType() instanceof JavaSdkType)) throw new RuntimeConfigurationError("Select a Java SDK for Payara Server.");
            if (sdk.getHomePath() == null || !Files.isDirectory(Path.of(sdk.getHomePath()))) throw new RuntimeConfigurationError("The selected Java SDK installation does not exist.");
            var names = new HashSet<String>();
            for (Deployment deployment : deployments) {
                if (deployment.artifactName().isEmpty() == deployment.filePath().isEmpty()) {
                    throw new RuntimeConfigurationError("Select either a project artifact or an EAR/WAR file.");
                }
                Path output = resolveDeployment(deployment);
                String name = deploymentName(deployment, output);
                if (!names.add(name)) throw new RuntimeConfigurationError("Duplicate application name: " + name);
            }
        } catch (InvalidPathException e) {
            throw new RuntimeConfigurationError("Invalid server or artifact path: " + e.getInput());
        }
    }

    public List<List<String>> createDeploymentCommands() throws RuntimeConfigurationError {
        var commands = new ArrayList<List<String>>();
        for (Deployment deployment : deployments) {
            Path output = resolveDeployment(deployment);
            boolean archive = Files.isRegularFile(output) && output.toString().matches("(?i).*\\.(ear|war)$");
            boolean exploded = Files.isDirectory(output) && (Files.isDirectory(output.resolve("WEB-INF")) || Files.isRegularFile(output.resolve("META-INF/application.xml")));
            if (!archive && !exploded) throw new RuntimeConfigurationError("Build the EAR/WAR artifact before running: " + output);
            commands.add(List.of("deploy", "--force=true", "--name", deploymentName(deployment, output), output.toString()));
        }
        return commands;
    }

    private Element getServerConfig(Element root) throws RuntimeConfigurationError {
        String configName = "server-config";
        Element servers = root.getChild("servers");
        if (servers != null) {
            for (Element server : servers.getChildren("server")) {
                if ("server".equals(server.getAttributeValue("name"))) configName = server.getAttributeValue("config-ref", configName);
            }
        }
        Element configs = root.getChild("configs");
        if (configs != null) {
            for (Element config : configs.getChildren("config")) {
                if (!configName.equals(config.getAttributeValue("name"))) continue;
                return config;
            }
        }
        throw new RuntimeConfigurationError("Server configuration not found in domain.xml: " + configName);
    }

    private String getDebugOptions() throws IOException, JDOMException, RuntimeConfigurationError {
        Element java = getServerConfig(JDOMUtil.load(getDomainDirectory().resolve("config/domain.xml"))).getChild("java-config");
        return java == null ? "" : java.getAttributeValue("debug-options", "")
                .replace("-agentlib:jdwp=", "").replace("-Xrunjdwp:", "");
    }

    public boolean isDebugServerListening() throws IOException, JDOMException, RuntimeConfigurationError {
        return Pattern.compile("(?:^|,)server=y(?:,|$)").matcher(getDebugOptions()).find();
    }

    public String getDebugPort() throws IOException, JDOMException, RuntimeConfigurationError {
        String options = getDebugOptions();
        var matcher = Pattern.compile("(?:^|,)address=(?:[^,]*:)?(\\d+)(?:,|$)").matcher(options);
        if (options.contains("transport=dt_socket") && matcher.find()) return validPort(matcher.group(1));
        throw new RuntimeConfigurationError("Configure a socket debug port in the domain's java-config debug-options.");
    }

    public String getAdminPort() throws IOException, JDOMException, RuntimeConfigurationError {
        return getListenerPort("admin-listener");
    }

    public String getListenerPort(String listenerName) throws IOException, JDOMException, RuntimeConfigurationError {
        Element root = JDOMUtil.load(getDomainDirectory().resolve("config/domain.xml"));
        Element config = getServerConfig(root);
        Element network = config.getChild("network-config");
        Element listeners = network == null ? null : network.getChild("network-listeners");
        if (listeners != null) {
            for (Element listener : listeners.getChildren("network-listener")) {
                if (!listenerName.equals(listener.getAttributeValue("name"))) continue;
                String value = listener.getAttributeValue("port", "");
                if (value.startsWith("${") && value.endsWith("}")) {
                    String key = value.substring(2, value.length() - 1);
                    for (Element scope : List.of(root, config)) {
                        for (Element property : scope.getChildren("system-property")) {
                            if (key.equals(property.getAttributeValue("name"))) value = property.getAttributeValue("value", value);
                        }
                    }
                    Element servers = root.getChild("servers");
                    if (servers != null) {
                        for (Element server : servers.getChildren("server")) {
                            if (!"server".equals(server.getAttributeValue("name"))) continue;
                            for (Element property : server.getChildren("system-property")) {
                                if (key.equals(property.getAttributeValue("name"))) value = property.getAttributeValue("value", value);
                            }
                        }
                    }
                }
                return validPort(value);
            }
        }
        throw new RuntimeConfigurationError("Configure a " + listenerName + " port in domain.xml.");
    }

    private static String validPort(String value) throws RuntimeConfigurationError {
        try {
            int port = Integer.parseInt(value);
            if (port > 0 && port <= 65535) return Integer.toString(port);
        } catch (NumberFormatException ignored) {
        }
        throw new RuntimeConfigurationError("Invalid domain port: " + value);
    }

    @Override
    public @NotNull SettingsEditor<ServerLocalConfiguration> getConfigurationEditor() {
        var group = new SettingsEditorGroup<ServerLocalConfiguration>();
        group.addEditor("Server", new ServerLocalSettingsEditor(getProject()));
        group.addEditor("Artifacts", new ServerArtifactsEditor(getProject()));
        return group;
    }

    @Override
    public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) {
        return new ServerLocalRunState(environment, this);
    }

    @Override
    public void readExternal(@NotNull Element element) throws InvalidDataException {
        super.readExternal(element);
        Element local = element.getChild("localServer");
        if (local == null) return;
        local = local.clone();
        PathMacroManager.getInstance(getProject()).expandPaths(local);
        serverHome = local.getAttributeValue("home", "");
        domainName = local.getAttributeValue("domain", "domain1");
        jdkName = local.getAttributeValue("jdk", "");
        deployments = new ArrayList<>();
        for (Element deployment : local.getChildren("deployment")) {
            deployments.add(new Deployment(deployment.getAttributeValue("artifact", ""), deployment.getAttributeValue("file", ""), deployment.getAttributeValue("name", "")));
        }
    }

    @Override
    public void writeExternal(@NotNull Element element) throws WriteExternalException {
        super.writeExternal(element);
        Element local = new Element("localServer").setAttribute("home", serverHome).setAttribute("domain", domainName).setAttribute("jdk", jdkName);
        for (Deployment deployment : deployments) {
            local.addContent(new Element("deployment").setAttribute("artifact", deployment.artifactName()).setAttribute("file", deployment.filePath()).setAttribute("name", deployment.applicationName()));
        }
        PathMacroManager.getInstance(getProject()).collapsePaths(local);
        element.addContent(local);
    }

    @Override
    public ServerLocalConfiguration clone() {
        var copy = (ServerLocalConfiguration) super.clone();
        copy.deployments = new ArrayList<>(deployments);
        return copy;
    }
}
