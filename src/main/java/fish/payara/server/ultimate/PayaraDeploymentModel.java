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

import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.javaee.appServers.deployment.DeploymentSource;
import com.intellij.javaee.appServers.run.configuration.CommonModel;
import com.intellij.javaee.oss.server.JavaeeDeploymentModel;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import fish.payara.server.local.ServerLocalConfiguration;
import org.jdom.Element;

import java.io.File;
import java.util.List;

public final class PayaraDeploymentModel extends JavaeeDeploymentModel {
    private String applicationName = "";

    public PayaraDeploymentModel(CommonModel model, DeploymentSource source) { super(model, source); }
    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String value) { applicationName = value; }

    List<String> deploymentCommand(File file) throws RuntimeConfigurationError {
        var configuration = ((PayaraServerModel) getServerModel()).localConfiguration();
        configuration.setDeployments(List.of(new ServerLocalConfiguration.Deployment("", file.getPath(), applicationName)));
        return configuration.createDeploymentCommands().getFirst();
    }

    String deploymentName(File file) throws RuntimeConfigurationError {
        // Status and undeploy must still work when the local archive has been removed.
        return applicationName.isEmpty() ? ServerLocalConfiguration.applicationName(file.toPath())
                : ServerLocalConfiguration.validateApplicationName(applicationName);
    }

    @Override public void readExternal(Element element) throws InvalidDataException {
        super.readExternal(element);
        applicationName = element.getAttributeValue("payara-name", "");
    }

    @Override public void writeExternal(Element element) throws WriteExternalException {
        super.writeExternal(element);
        if (!applicationName.isEmpty()) element.setAttribute("payara-name", applicationName);
    }
}
