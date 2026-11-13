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
import com.intellij.javaee.appServers.deployment.DeploymentSource;
import com.intellij.javaee.appServers.run.configuration.CommonModel;
import com.intellij.javaee.facet.JavaeeFacet;
import com.intellij.javaee.oss.descriptor.JavaeeDescriptorsManager;
import com.intellij.javaee.oss.server.JavaeeIntegration;
import com.intellij.javaee.oss.server.JavaeeDeploymentProvider;
import com.intellij.javaee.oss.server.JavaeePersistentData;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.packaging.artifacts.ArtifactType;
import fish.payara.server.local.ServerLocalConfiguration;

import javax.swing.Icon;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Collection;
import java.util.jar.JarFile;

import static fish.payara.PayaraConstants.PAYARA_ICON;

public final class PayaraServerIntegration extends JavaeeIntegration {
    @Override public String getName() { return "Payara Server"; }
    @Override public Icon getIcon() { return PAYARA_ICON; }
    @Override public Icon getBigIcon() { return PAYARA_ICON; }
    @Override public String getNameFromTemplate(String path) { return "Payara Server"; }
    @Override public String getVersionFromTemplate(String path) { return ""; }
    @Override public boolean isAsyncWrapped() { return true; }
    @Override public JavaeeDeploymentProvider getDeploymentProvider(boolean local) {
        return new JavaeeDeploymentProvider() {
            @Override public boolean isDeployOrderMatter() { return true; }
            @Override public Collection<? extends ArtifactType> getSupportedArtifactTypes() {
                return super.getSupportedArtifactTypes().stream()
                        .filter(type -> List.of("ear", "war", "exploded-ear", "exploded-war").contains(type.getId())).toList();
            }
        };
    }
    @Override protected void collectDescriptors(JavaeeDescriptorsManager manager) { }
    @Override public String getContextRoot(JavaeeFacet facet) { return null; }

    @Override
    public String getServerVersion(JavaeePersistentData data) throws IOException {
        Path kernel = ServerLocalConfiguration.glassfishHome(data.HOME).resolve("modules/kernel.jar");
        try (var jar = new JarFile(kernel.toFile())) {
            var manifest = jar.getManifest();
            String version = manifest == null ? null : manifest.getMainAttributes().getValue("Bundle-Version");
            if (version == null || version.isBlank()) throw new IOException("Cannot read the Payara Server version: " + kernel);
            return version;
        }
    }

    @Override
    protected void checkValidServerHome(String home, String version) throws IOException {
        Path glassfish = ServerLocalConfiguration.glassfishHome(home);
        if (!Files.isRegularFile(glassfish.resolve("lib/client/appserver-cli.jar"))) {
            throw new IOException("Select a Payara Server installation containing glassfish/lib/client/appserver-cli.jar.");
        }
    }

    @Override
    protected void addLibraryLocations(String home, List<Path> locations) {
        locations.add(ServerLocalConfiguration.glassfishHome(home).resolve("modules"));
    }

    @Override
    public DeploymentModel createNewDeploymentModel(CommonModel model, DeploymentSource source) {
        return new PayaraDeploymentModel(model, source);
    }

    @Override
    public SettingsEditor<DeploymentModel> createAdditionalDeploymentSettingsEditor(CommonModel model, DeploymentSource source) {
        return new PayaraDeploymentEditor();
    }
}
