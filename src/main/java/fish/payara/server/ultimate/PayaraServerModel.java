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
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.javaee.appServers.deployment.DeploymentModel;
import com.intellij.javaee.appServers.run.configuration.CommonModel;
import com.intellij.javaee.oss.admin.JavaeeAdmin;
import com.intellij.javaee.oss.server.JavaeeRunSettingsEditor;
import com.intellij.javaee.oss.server.JavaeeServerInstance;
import com.intellij.javaee.oss.server.JavaeeServerModel;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.util.ui.FormBuilder;
import fish.payara.server.PayaraServerConfigurationType;
import fish.payara.server.local.ServerLocalConfiguration;
import fish.payara.server.local.ServerLocalConfigurationFactory;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.util.HashSet;
import org.jdom.JDOMException;

public final class PayaraServerModel extends JavaeeServerModel {
    public String DOMAIN = "domain1";

    ServerLocalConfiguration localConfiguration() {
        var configuration = new ServerLocalConfiguration(getCommonModel().getProject(),
                new ServerLocalConfigurationFactory(new PayaraServerConfigurationType()), "Payara");
        configuration.setServerHome(getHome());
        configuration.setDomainName(DOMAIN);
        return configuration;
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        super.checkConfiguration();
        checkHasJdk();
        var configuration = localConfiguration();
        configuration.checkDomain();
        var names = new HashSet<String>();
        for (var deployment : getCommonModel().getDeploymentModels()) {
            var source = deployment.getDeploymentSource().getFile();
            if (source != null && !names.add(((PayaraDeploymentModel) deployment).deploymentName(source))) {
                throw new RuntimeConfigurationError("Duplicate Payara application name: " + ((PayaraDeploymentModel) deployment).deploymentName(source));
            }
        }
        try {
            configuration.getAdminPort();
            configuration.getListenerPort("http-listener-1");
        } catch (IOException | JDOMException e) {
            throw new RuntimeConfigurationError(e.getMessage());
        }
    }

    @Override protected String getDefaultUsername() { return "admin"; }
    @Override protected String getDefaultPassword() { return ""; }
    @Override protected boolean isEmptyPasswordAllowed() { return true; }
    @Override public boolean undeployBeforeDeploy() { return false; }
    @Override public boolean isDetectableLocalPort() { return false; }
    @Override public boolean isDetectableServerPort() { return false; }
    @Override protected String getLogFileId() { return "Payara server.log"; }
    @Override protected String getLogFilePath(String id) { return localConfiguration().getDomainDirectory().resolve("logs/server.log").toString(); }
    @Override protected JavaeeAdmin createServerAdmin(JavaeeServerInstance instance) { return new PayaraAdmin(this, instance); }

    @Override protected int getServerPort() { return port("admin-listener", 4848); }
    @Override public int getLocalPort() { return port("http-listener-1", 8080); }

    private int port(String listener, int fallback) {
        if (getCommonModel() == null || getCommonModel().getApplicationServer() == null) return fallback;
        try {
            return Integer.parseInt(localConfiguration().getListenerPort(listener));
        } catch (IOException | JDOMException | RuntimeConfigurationError | InvalidPathException e) {
            // Unconfigured templates are displayed before an application server is selected.
            return fallback;
        }
    }

    @Override
    public SettingsEditor<CommonModel> getEditor() {
        return new JavaeeRunSettingsEditor<PayaraServerModel>(getIntegration()) {
            private final JTextField domain = new JTextField();
            private final JTextField username = new JTextField();
            private final JPasswordField password = new JPasswordField();
            private final JPanel panel = FormBuilder.createFormBuilder()
                    .addLabeledComponent("Domain:", domain)
                    .addLabeledComponent("Username:", username)
                    .addLabeledComponent("Password:", password).getPanel();

            @Override protected JComponent getEditor() { return panel; }
            @Override protected void resetEditorFrom(PayaraServerModel model) {
                domain.setText(model.DOMAIN);
                username.setText(model.USERNAME);
                password.setText(model.getPassword());
            }
            @Override protected void applyEditorTo(PayaraServerModel model) {
                model.DOMAIN = domain.getText().trim();
                model.USERNAME = username.getText().trim();
                model.setPassword(new String(password.getPassword()));
            }
        };
    }
}
