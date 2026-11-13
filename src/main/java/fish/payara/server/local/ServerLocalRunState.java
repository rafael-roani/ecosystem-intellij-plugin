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

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaCommandLineState;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.RemoteConnection;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

final class ServerLocalRunState extends JavaCommandLineState {
    // shortcut: readiness follows Payara's startup log; update this marker if its log format changes.
    private static final Pattern STARTED = Pattern.compile("Payara Server[^\\r\\n]*startup time");
    private final ServerLocalConfiguration configuration;
    private List<List<String>> deployments = List.of();
    private String adminPort;

    ServerLocalRunState(ExecutionEnvironment environment, ServerLocalConfiguration configuration) {
        super(environment);
        this.configuration = configuration.clone();
    }

    private boolean isDebug() {
        return DefaultDebugExecutor.EXECUTOR_ID.equals(getEnvironment().getExecutor().getId());
    }

    private JavaParameters asadminParameters() {
        return configuration.createAsadminParameters(configuration.getJdk());
    }

    @Override
    protected JavaParameters createJavaParameters() throws ExecutionException {
        try {
            configuration.checkConfiguration();
            deployments = configuration.createDeploymentCommands();
            adminPort = configuration.getAdminPort();
            if (isDebug()) configuration.getDebugPort();
            var parameters = asadminParameters();
            parameters.getProgramParametersList().addAll("start-domain", "--verbose=true", "--domaindir",
                    configuration.getGlassfishHome().resolve("domains").toString(), "--debug=" + isDebug(), configuration.getDomainName());
            return parameters;
        } catch (Exception e) {
            throw new ExecutionException(e.getMessage(), e);
        }
    }

    @Override
    protected boolean shouldPrepareDebuggerConnection() {
        // The debugger attaches to the server child process, not to the asadmin JVM.
        return false;
    }

    @Override
    public RemoteConnection createRemoteConnection(ExecutionEnvironment environment) {
        if (!isDebug()) return null;
        try {
            return new RemoteConnection(true, "localhost", configuration.getDebugPort(), false);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public boolean isPollConnection() {
        return true;
    }

    @Override
    protected OSProcessHandler startProcess() throws ExecutionException {
        OSProcessHandler handler = super.startProcess();
        handler.setShouldDestroyProcessRecursively(true);
        var started = new AtomicBoolean();
        var deploymentProcess = new AtomicReference<OSProcessHandler>();
        handler.addProcessListener(new ProcessAdapter() {
            private final StringBuilder output = new StringBuilder();

            @Override
            public synchronized void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                if (started.get() || deployments.isEmpty()) return;
                output.append(event.getText());
                if (STARTED.matcher(output).find() && started.compareAndSet(false, true)) {
                    ApplicationManager.getApplication().executeOnPooledThread(() -> deploy(handler, deploymentProcess));
                }
                if (output.length() > 8192) output.delete(0, output.length() - 4096);
            }

            @Override
            public void processWillTerminate(@NotNull ProcessEvent event, boolean willBeDestroyed) {
                OSProcessHandler child = deploymentProcess.get();
                if (child != null && !child.isProcessTerminated()) child.destroyProcess();
            }
        });
        return handler;
    }

    private void deploy(OSProcessHandler server, AtomicReference<OSProcessHandler> current) {
        for (List<String> command : deployments) {
            if (server.isProcessTerminating() || server.isProcessTerminated()) return;
            try {
                var parameters = asadminParameters();
                // shortcut: use asadmin's saved login; add a credential editor when separate accounts are needed.
                parameters.getProgramParametersList().addAll("--host", "localhost", "--port", adminPort);
                parameters.getProgramParametersList().addAll(command);
                server.notifyTextAvailable("\nDeploying " + command.get(3) + "...\n", ProcessOutputTypes.SYSTEM);
                var process = new OSProcessHandler(parameters.toCommandLine());
                process.setShouldDestroyProcessRecursively(true);
                current.set(process);
                process.addProcessListener(new ProcessAdapter() {
                    @Override
                    public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                        server.notifyTextAvailable(event.getText(), outputType);
                    }
                });
                process.startNotify();
                if (server.isProcessTerminating() || server.isProcessTerminated()) process.destroyProcess();
                if (!process.waitFor(600_000)) {
                    process.destroyProcess();
                    throw new ExecutionException("Deployment timed out: " + command.get(3));
                }
                if (!Integer.valueOf(0).equals(process.getExitCode())) {
                    throw new ExecutionException("Deployment failed: " + command.get(3) + ". Remaining deployments were skipped.");
                }
            } catch (ExecutionException e) {
                server.notifyTextAvailable(e.getMessage() + "\n", ProcessOutputTypes.STDERR);
                return;
            } finally {
                current.set(null);
            }
        }
        server.notifyTextAvailable("\nAll Payara artifacts deployed.\n", ProcessOutputTypes.SYSTEM);
    }
}
