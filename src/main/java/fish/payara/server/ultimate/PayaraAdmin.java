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

import com.intellij.execution.ExecutionException;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.javaee.appServers.deployment.DeploymentModel;
import com.intellij.javaee.appServers.deployment.DeploymentStatus;
import com.intellij.javaee.oss.admin.JavaeeAdmin;
import com.intellij.javaee.oss.admin.JavaeeAdminDeployCallback;
import com.intellij.javaee.oss.admin.JavaeeAdminStartCallback;
import com.intellij.javaee.oss.server.JavaeeServerExtension;
import com.intellij.javaee.oss.server.JavaeeServerInstance;
import com.intellij.openapi.progress.ProgressManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

final class PayaraAdmin implements JavaeeAdmin {
    private static final Pattern APPLICATION = Pattern.compile("(?m)^([^\\r\\n]+?)\\h+<");

    private final PayaraServerModel model;
    private final JavaeeServerInstance instance;
    private final AtomicReference<CapturingProcessHandler> current = new AtomicReference<>();
    private volatile boolean closed;
    private String host;
    private int port;
    private String username;
    private String password;
    private String connectionError = "";

    PayaraAdmin(PayaraServerModel model, JavaeeServerInstance instance) {
        this.model = model;
        this.instance = instance;
    }

    @Override
    public synchronized void start(String host, int port, String username, String password, JavaeeAdminStartCallback callback) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    @Override
    public boolean doConnect() {
        try {
            execute(List.of("list-applications"), 10_000);
            return true;
        } catch (ExecutionException e) {
            var handler = instance.getProcessHandler();
            if (handler != null && !e.getMessage().equals(connectionError)) {
                handler.notifyTextAvailable(e.getMessage() + "\n", ProcessOutputTypes.STDERR);
                connectionError = e.getMessage();
            }
            return false;
        }
    }

    @Override
    public void shutdown() {
        closed = true;
        var process = current.get();
        if (process != null && !process.isProcessTerminated()) process.destroyProcess();
    }

    @Override public void doDisconnect() { shutdown(); }
    @Override public JavaeeServerExtension getExtension() { return null; }
    // The IDE copies exploded resources and performs debugger HotSwap itself.
    @Override public void updateChangedFiles(List<String> files) { }
    @Override public void cleanDeployments(List<DeploymentModel> deployments) { }

    @Override
    public void startDeploy(DeploymentModel deployment, File file, JavaeeAdminDeployCallback callback) {
        try {
            callback.setDeploymentStatus(deployment, DeploymentStatus.PREPARING);
            var output = execute(payaraDeployment(deployment).deploymentCommand(file), 600_000);
            callback.showDeploymentMessage(deployment, output.getStdout());
            callback.setDeploymentStatus(deployment, DeploymentStatus.DEPLOYED);
        } catch (ExecutionException | com.intellij.execution.configurations.RuntimeConfigurationError e) {
            callback.setDeploymentStatus(deployment, DeploymentStatus.FAILED, e);
        }
    }

    @Override
    public void startUndeploy(DeploymentModel deployment, File file, JavaeeAdminDeployCallback callback) {
        try {
            callback.setDeploymentStatus(deployment, DeploymentStatus.DEACTIVATING);
            var output = execute(List.of("undeploy", payaraDeployment(deployment).deploymentName(file)), 600_000);
            callback.showDeploymentMessage(deployment, output.getStdout());
            callback.setDeploymentStatus(deployment, DeploymentStatus.NOT_DEPLOYED);
        } catch (ExecutionException | com.intellij.execution.configurations.RuntimeConfigurationError e) {
            callback.setDeploymentStatus(deployment, DeploymentStatus.FAILED, e);
        }
    }

    @Override
    public void startUpdateDeploymentStatus(DeploymentModel deployment, File file, JavaeeAdminDeployCallback callback) {
        try {
            var output = execute(List.of("list-applications"), 30_000);
            String name = payaraDeployment(deployment).deploymentName(file);
            boolean deployed = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\s+<").matcher(output.getStdout()).find();
            callback.setDeploymentStatus(deployment, deployed ? DeploymentStatus.DEPLOYED : DeploymentStatus.NOT_DEPLOYED);
        } catch (ExecutionException | com.intellij.execution.configurations.RuntimeConfigurationError e) {
            callback.setDeploymentStatus(deployment, DeploymentStatus.UNKNOWN, e);
        }
    }

    List<String> undeployAll() throws ExecutionException {
        var names = applicationNames(execute(List.of("list-applications"), 30_000).getStdout());
        var failures = new ArrayList<String>();
        for (String name : names) {
            try { execute(List.of("undeploy", name), 600_000); }
            catch (ExecutionException e) { failures.add(name + ": " + e.getMessage()); }
        }
        if (!failures.isEmpty()) throw new ExecutionException(String.join("\n", failures));
        return names;
    }

    static List<String> applicationNames(String terseListOutput) {
        return APPLICATION.matcher(terseListOutput).results().map(match -> match.group(1)).toList();
    }

    private static PayaraDeploymentModel payaraDeployment(DeploymentModel model) throws ExecutionException {
        if (model instanceof PayaraDeploymentModel payara) return payara;
        throw new ExecutionException("Unsupported Payara deployment configuration.");
    }

    private synchronized ProcessOutput execute(List<String> command, int timeout) throws ExecutionException {
        ProgressManager.checkCanceled();
        if (closed) throw new ExecutionException("Payara connection was closed.");
        var parameters = model.localConfiguration().createAsadminParameters(model.getJre());
        var arguments = parameters.getProgramParametersList();
        arguments.addAll("--terse=true", "--host", host, "--port", Integer.toString(port));
        if (username != null && !username.isBlank()) arguments.addAll("--user", username);
        Path passwordFile = null;
        try {
            if (password != null && !password.isEmpty()) {
                // Files.createTempFile uses private permissions on POSIX filesystems.
                passwordFile = Files.createTempFile("payara-admin-", ".properties");
                var properties = new Properties();
                properties.setProperty("AS_ADMIN_PASSWORD", password);
                try (var stream = Files.newOutputStream(passwordFile)) { properties.store(stream, null); }
                arguments.addAll("--passwordfile", passwordFile.toString());
            }
            arguments.addAll(command);
            var process = new CapturingProcessHandler(parameters.toCommandLine());
            process.setShouldDestroyProcessRecursively(true);
            current.set(process);
            if (closed) process.destroyProcess();
            ProcessOutput output = process.runProcess(timeout);
            ProgressManager.checkCanceled();
            if (output.isTimeout() || output.getExitCode() != 0) {
                throw new ExecutionException("Payara " + command.getFirst() + " failed: " + output.getStdout() + output.getStderr());
            }
            return output;
        } catch (IOException e) {
            throw new ExecutionException("Cannot prepare Payara admin credentials.", e);
        } finally {
            current.set(null);
            if (passwordFile != null) {
                try { Files.deleteIfExists(passwordFile); }
                catch (IOException e) { passwordFile.toFile().deleteOnExit(); }
            }
        }
    }
}
