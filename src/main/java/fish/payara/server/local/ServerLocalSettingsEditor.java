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

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdkType;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

final class ServerLocalSettingsEditor extends SettingsEditor<ServerLocalConfiguration> {
    private final TextFieldWithBrowseButton home = new TextFieldWithBrowseButton();
    private final JTextField domain = new JTextField();
    private final JComboBox<String> jdk = new JComboBox<>();
    private final JPanel panel;

    ServerLocalSettingsEditor(Project project) {
        home.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Payara Server Installation"));
        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Payara home:", home)
                .addLabeledComponent("Domain:", domain)
                .addLabeledComponent("JDK:", jdk)
                .addComponentFillVertically(new JPanel(), 0).getPanel();
    }

    @Override
    protected void resetEditorFrom(@NotNull ServerLocalConfiguration configuration) {
        home.setText(configuration.getServerHome());
        domain.setText(configuration.getDomainName());
        jdk.removeAllItems();
        for (Sdk sdk : ProjectJdkTable.getInstance().getAllJdks()) {
            if (sdk.getSdkType() instanceof JavaSdkType) jdk.addItem(sdk.getName());
        }
        jdk.setSelectedItem(configuration.getJdkName());
    }

    @Override
    protected void applyEditorTo(@NotNull ServerLocalConfiguration configuration) {
        configuration.setServerHome(home.getText().trim());
        configuration.setDomainName(domain.getText().trim());
        configuration.setJdkName(jdk.getSelectedItem() instanceof String value ? value : "");
    }

    @Override
    protected @NotNull JComponent createEditor() {
        return panel;
    }
}
