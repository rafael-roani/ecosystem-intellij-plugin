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
package fish.payara.server.ultimate;

import com.intellij.execution.configurations.DebuggingRunnerData;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.util.EnvironmentVariable;
import com.intellij.debugger.impl.GenericDebuggerRunnerSettings;
import com.intellij.debugger.settings.DebuggerSettings;
import com.intellij.javaee.appServers.run.localRun.ExecutableObject;
import com.intellij.javaee.appServers.run.localRun.ColoredCommandLineExecutableObject;
import com.intellij.javaee.oss.server.JavaeeParameters;
import com.intellij.javaee.oss.server.JavaeeStartupPolicy;
import com.intellij.openapi.projectRoots.JavaSdkType;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.jdom.JDOMException;

final class PayaraStartupPolicy extends JavaeeStartupPolicy<PayaraServerModel> {
    @Override
    protected void getStartupParameters(JavaeeParameters parameters, PayaraServerModel model, boolean debug) {
        asadmin(parameters, model);
        parameters.add("start-domain", "--debug=" + debug, "--domaindir",
                model.localConfiguration().getGlassfishHome().resolve("domains").toString(), model.DOMAIN);
    }

    @Override
    protected void getShutdownParameters(JavaeeParameters parameters, PayaraServerModel model, boolean debug) {
        asadmin(parameters, model);
        parameters.add("stop-domain", "--domaindir",
                model.localConfiguration().getGlassfishHome().resolve("domains").toString(), model.DOMAIN);
    }

    private static void asadmin(JavaeeParameters parameters, PayaraServerModel model) {
        var sdk = model.getJre();
        String java = sdk != null && sdk.getHomePath() != null && sdk.getSdkType() instanceof JavaSdkType javaSdk
                ? javaSdk.getVMExecutablePath(sdk) : "java";
        parameters.add(java, "-jar", model.localConfiguration().getGlassfishHome().resolve("lib/client/appserver-cli.jar").toString(),
                "--interactive=false");
    }

    @Override
    protected ExecutableObject getDefaultStartupScript(PayaraServerModel model, boolean debug) {
        if (model.getCommonModel().getApplicationServer() == null) return null;
        var parameters = new JavaeeParameters();
        getStartupParameters(parameters, model, debug);
        return executable(model, parameters);
    }

    @Override
    protected ExecutableObject getDefaultShutdownScript(PayaraServerModel model, boolean debug) {
        if (model.getCommonModel().getApplicationServer() == null) return null;
        var parameters = new JavaeeParameters();
        getShutdownParameters(parameters, model, debug);
        return executable(model, parameters);
    }

    private static ExecutableObject executable(PayaraServerModel model, JavaeeParameters parameters) {
        String directory = model.localConfiguration().getGlassfishHome().resolve("bin").toString();
        return new ColoredCommandLineExecutableObject(parameters.get(), null) {
            @Override public OSProcessHandler createProcessHandler(String workingDirectory, Map<String, String> environment) throws ExecutionException {
                return super.createProcessHandler(workingDirectory == null ? directory : workingDirectory, environment);
            }
        };
    }

    @Override
    protected List<EnvironmentVariable> getEnvironmentVariables(PayaraServerModel model) {
        var sdk = model.getJre();
        if (sdk == null || sdk.getHomePath() == null) return List.of();
        return List.of(new EnvironmentVariable("JAVA_HOME", sdk.getHomePath(), true),
                new EnvironmentVariable("AS_JAVA", sdk.getHomePath(), true));
    }

    @Override
    protected void initSettings(PayaraServerModel model, DebuggingRunnerData data) {
        try {
            data.setDebugPort(model.localConfiguration().getDebugPort());
            data.setLocal(true);
            if (data instanceof GenericDebuggerRunnerSettings generic) generic.setTransport(DebuggerSettings.SOCKET_TRANSPORT);
        } catch (IOException | JDOMException | RuntimeConfigurationError ignored) {
            // Unconfigured templates are validated before the server starts.
        }
    }

    @Override
    protected void checkSettings(PayaraServerModel model, DebuggingRunnerData data) throws RuntimeConfigurationException {
        model.checkConfiguration();
        try {
            var configuration = model.localConfiguration();
            if (configuration.isDebugServerListening()) {
                throw new RuntimeConfigurationError("Set server=n in the domain's java-config debug-options so IntelliJ can listen for the server JVM.");
            }
            data.setDebugPort(configuration.getDebugPort());
            data.setLocal(true);
            if (data instanceof GenericDebuggerRunnerSettings generic) generic.setTransport(DebuggerSettings.SOCKET_TRANSPORT);
        } catch (IOException | JDOMException e) {
            throw new RuntimeConfigurationError(e.getMessage());
        }
    }
}
