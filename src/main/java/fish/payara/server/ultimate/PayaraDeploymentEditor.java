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

import com.intellij.javaee.appServers.deployment.DeploymentModel;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;

final class PayaraDeploymentEditor extends SettingsEditor<DeploymentModel> {
    private final JTextField name = new JTextField();
    private final JPanel panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Application name (optional):", name).getPanel();

    @Override protected void resetEditorFrom(@NotNull DeploymentModel model) {
        name.setText(((PayaraDeploymentModel) model).getApplicationName());
    }
    @Override protected void applyEditorTo(@NotNull DeploymentModel model) {
        ((PayaraDeploymentModel) model).setApplicationName(name.getText().trim());
    }
    @Override protected @NotNull JComponent createEditor() { return panel; }
}
