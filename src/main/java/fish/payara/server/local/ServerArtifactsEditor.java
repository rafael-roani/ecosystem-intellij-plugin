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

import com.intellij.ide.util.ChooseElementsDialog;
import com.intellij.execution.RunManagerEx;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactManager;
import com.intellij.packaging.impl.run.BuildArtifactsBeforeRunTaskProvider;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBList;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.BorderLayout;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

final class ServerArtifactsEditor extends SettingsEditor<ServerLocalConfiguration> {
    private final Project project;
    private final CollectionListModel<ServerLocalConfiguration.Deployment> model = new CollectionListModel<>();
    private final JBList<ServerLocalConfiguration.Deployment> list = new JBList<>(model);
    private final JPanel panel = new JPanel(new BorderLayout(0, 8));

    ServerArtifactsEditor(Project project) {
        this.project = project;
        var label = new JLabel("Deploy these artifacts after the domain starts, in the listed order:");
        label.setLabelFor(list);
        panel.add(label, BorderLayout.NORTH);
        model.addListDataListener(new ListDataListener() {
            @Override public void intervalAdded(ListDataEvent event) { fireEditorStateChanged(); }
            @Override public void intervalRemoved(ListDataEvent event) { fireEditorStateChanged(); }
            @Override public void contentsChanged(ListDataEvent event) { fireEditorStateChanged(); }
        });
        panel.add(ToolbarDecorator.createDecorator(list)
                .setAddAction(button -> chooseSource())
                .setEditAction(button -> editName())
                .setEditActionName("Edit application name")
                .createPanel(), BorderLayout.CENTER);
    }

    private void editName() {
        int index = list.getSelectedIndex();
        if (index < 0) return;
        var deployment = model.getElementAt(index);
        String name = Messages.showInputDialog(project, "Application name (empty uses the archive name):", "Payara Deployment", null, deployment.applicationName(), null);
        if (name == null) return;
        var items = new ArrayList<>(model.getItems());
        items.set(index, new ServerLocalConfiguration.Deployment(deployment.artifactName(), deployment.filePath(), name.trim()));
        model.replaceAll(items);
        list.setSelectedIndex(index);
    }

    private void chooseSource() {
        var menu = new JPopupMenu();
        var artifact = new JMenuItem("Project artifact...");
        artifact.addActionListener(event -> chooseArtifacts());
        menu.add(artifact);
        var external = new JMenuItem("External EAR/WAR...");
        external.addActionListener(event -> chooseFiles());
        menu.add(external);
        menu.show(list, 0, 0);
    }

    private void chooseArtifacts() {
        var artifacts = Arrays.stream(ArtifactManager.getInstance(project).getSortedArtifacts())
                .filter(artifact -> {
                    String type = artifact.getArtifactType().getId();
                    String path = artifact.getOutputFilePath();
                    return type.equals("war") || type.equals("ear") || type.equals("exploded-war") || type.equals("exploded-ear")
                            || path != null && path.matches("(?i).*\\.(ear|war)$");
                }).toList();
        if (artifacts.isEmpty()) {
            Messages.showInfoMessage(project, "Create an EAR/WAR artifact in Project Structure > Artifacts, or select an external archive.", "Payara Artifacts");
            return;
        }
        var dialog = new ChooseElementsDialog<Artifact>(project, artifacts, "Select Artifacts", "") {
            @Override protected String getItemText(Artifact item) { return item.getName(); }
            @Override protected Icon getItemIcon(Artifact item) { return item.getArtifactType().getIcon(); }
        };
        if (dialog.showAndGet()) {
            for (Artifact artifact : dialog.getChosenElements()) add(new ServerLocalConfiguration.Deployment(artifact.getName(), ""));
        }
    }

    private void chooseFiles() {
        var descriptor = new FileChooserDescriptor(true, true, false, false, false, true)
                .withTitle("Select EAR/WAR Archives or Exploded Applications");
        FileChooser.chooseFiles(descriptor, project, null, files -> {
            for (var file : files) add(new ServerLocalConfiguration.Deployment("", file.getPath()));
        });
    }

    private void add(ServerLocalConfiguration.Deployment deployment) {
        if (!model.getItems().contains(deployment)) model.add(deployment);
    }

    @Override
    protected void resetEditorFrom(@NotNull ServerLocalConfiguration configuration) {
        model.replaceAll(configuration.getDeployments());
    }

    @Override
    protected void applyEditorTo(@NotNull ServerLocalConfiguration configuration) {
        var removed = configuration.getDeployments().stream()
                .filter(deployment -> !deployment.artifactName().isEmpty()
                        && model.getItems().stream().noneMatch(item -> item.artifactName().equals(deployment.artifactName())))
                .map(ServerLocalConfiguration.Deployment::artifactName).collect(Collectors.toSet());
        for (var task : RunManagerEx.getInstanceEx(project).getBeforeRunTasks(configuration, BuildArtifactsBeforeRunTaskProvider.ID)) {
            for (var pointer : List.copyOf(task.getArtifactPointers())) {
                if (removed.contains(pointer.getArtifactName())) task.removeArtifact(pointer);
            }
            if (task.getItemsCount() == 0) task.setEnabled(false);
        }
        configuration.setDeployments(model.getItems());
        for (var deployment : model.getItems()) {
            if (!deployment.artifactName().isEmpty()) {
                Artifact artifact = ArtifactManager.getInstance(project).findArtifact(deployment.artifactName());
                if (artifact != null) BuildArtifactsBeforeRunTaskProvider.setBuildArtifactBeforeRun(project, configuration, artifact);
            }
        }
    }

    @Override
    protected @NotNull JComponent createEditor() {
        return panel;
    }
}
