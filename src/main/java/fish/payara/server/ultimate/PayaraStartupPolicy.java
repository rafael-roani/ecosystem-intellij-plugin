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
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.javaee.appServers.run.configuration.CommonModel;
import com.intellij.javaee.appServers.run.configuration.JavaCommandLineStartupPolicy;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class PayaraStartupPolicy implements JavaCommandLineStartupPolicy {
    private static final String MAIN_CLASS = "com.sun.enterprise.glassfish.bootstrap.ASMain";

    @Override
    public JavaParameters createCommandLine(CommonModel common) throws ExecutionException {
        var model = (PayaraServerModel) common.getServerModel();
        try {
            model.checkConfiguration();
        } catch (com.intellij.execution.configurations.RuntimeConfigurationException e) {
            throw new ExecutionException(e.getMessage(), e);
        }
        var configuration = model.localConfiguration();
        var parameters = configuration.createAsadminParameters(model.getJre());
        parameters.getProgramParametersList().addAll("--terse=true", "start-domain", "--dry-run=true", "--verbose=true",
                "--debug=false", "--domaindir", configuration.getGlassfishHome().resolve("domains").toString(), model.DOMAIN);
        var output = new CapturingProcessHandler(parameters.toCommandLine()).runProcess(30_000);
        if (output.isTimeout() || output.getExitCode() != 0) {
            throw new ExecutionException("Cannot prepare Payara startup: " + output.getStdout() + output.getStderr());
        }
        return serverParameters(parameters, output.getStdout());
    }

    static JavaParameters serverParameters(JavaParameters cli, String output) throws ExecutionException {
        List<String> arguments = new ArrayList<>(output.lines().filter(line -> !line.isBlank()).toList());
        int main = arguments.indexOf(MAIN_CLASS);
        if (arguments.size() < 4 || !"-cp".equals(arguments.get(1)) || main < 3) {
            throw new ExecutionException("Unrecognized Payara start-domain --dry-run output.");
        }
        var parameters = new JavaParameters();
        parameters.setJdk(cli.getJdk());
        parameters.setEnv(cli.getEnv());
        parameters.setPassParentEnvs(true);
        parameters.setCharset(cli.getCharset());
        parameters.setWorkingDirectory(cli.getWorkingDirectory());
        for (String path : arguments.get(2).split(java.util.regex.Pattern.quote(File.pathSeparator))) parameters.getClassPath().add(path);
        parameters.getVMParametersList().addAll(arguments.subList(3, main));
        parameters.setMainClass(MAIN_CLASS);
        List<String> program = new ArrayList<>(arguments.subList(main + 1, arguments.size()));
        int stdin = program.indexOf("-read-stdin");
        // shortcut: use the saved/default domain master password; add an editor for unsaved custom master passwords.
        if (stdin >= 0 && stdin + 1 < program.size()) program.subList(stdin, stdin + 2).clear();
        parameters.getProgramParametersList().addAll(program);
        return parameters;
    }
}
