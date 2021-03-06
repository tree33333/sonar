/*
 * Sonar, open source software quality management tool.
 * Copyright (C) 2008-2012 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * Sonar is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Sonar is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Sonar; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.server.plugins;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.ServerComponent;
import org.sonar.api.platform.PluginMetadata;
import org.sonar.api.utils.Logs;
import org.sonar.api.utils.SonarException;
import org.sonar.api.utils.TimeProfiler;
import org.sonar.core.plugins.DefaultPluginMetadata;
import org.sonar.core.plugins.PluginInstaller;
import org.sonar.server.platform.DefaultServerFileSystem;
import org.sonar.server.platform.ServerStartException;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class PluginDeployer implements ServerComponent {

  private static final Logger LOG = LoggerFactory.getLogger(PluginDeployer.class);

  private DefaultServerFileSystem fileSystem;
  private Map<String, PluginMetadata> pluginByKeys = Maps.newHashMap();
  private PluginInstaller installer;

  public PluginDeployer(DefaultServerFileSystem fileSystem) {
    this(fileSystem, new PluginInstaller());
  }

  PluginDeployer(DefaultServerFileSystem fileSystem, PluginInstaller installer) {
    this.fileSystem = fileSystem;
    this.installer = installer;
  }

  public void start() throws IOException {
    TimeProfiler profiler = new TimeProfiler().start("Install plugins");

    deleteUninstalledPlugins();

    loadUserPlugins();
    moveAndLoadDownloadedPlugins();
    loadCorePlugins();

    deployPlugins();

    profiler.stop();
  }

  private void deleteUninstalledPlugins() {
    File trashDir = fileSystem.getRemovedPluginsDir();
    try {
      if (trashDir.exists()) {
        FileUtils.deleteDirectory(trashDir);
      }
    } catch (IOException e) {
      throw new SonarException("Fail to clean the plugin trash directory: " + trashDir, e);
    }
  }

  private void loadUserPlugins() throws IOException {
    for (File file : fileSystem.getUserPlugins()) {
      registerPlugin(file, false, false);
    }
  }

  private void registerPlugin(File file, boolean isCore, boolean canDelete) throws IOException {
    DefaultPluginMetadata metadata = installer.extractMetadata(file, isCore);
    if (StringUtils.isNotBlank(metadata.getKey())) {
      PluginMetadata existing = pluginByKeys.get(metadata.getKey());
      if (existing != null) {
        if (canDelete) {
          FileUtils.deleteQuietly(existing.getFile());
          Logs.INFO.info("Plugin " + metadata.getKey() + " replaced by new version");

        } else {
          throw new ServerStartException("Found two plugins with the same key '" + metadata.getKey() + "': " + metadata.getFile().getName() + " and "
              + existing.getFile().getName());
        }
      }
      pluginByKeys.put(metadata.getKey(), metadata);
    }
  }

  private void moveAndLoadDownloadedPlugins() throws IOException {
    if (fileSystem.getDownloadedPluginsDir().exists()) {
      Collection<File> jars = FileUtils.listFiles(fileSystem.getDownloadedPluginsDir(), new String[]{"jar"}, false);
      for (File jar : jars) {
        File movedJar = moveDownloadedFile(jar);
        if (movedJar != null) {
          registerPlugin(movedJar, false, true);
        }
      }
    }
  }

  private File moveDownloadedFile(File jar) {
    File destDir = fileSystem.getUserPluginsDir();
    File destFile = new File(destDir, jar.getName());
    if (destFile.exists()) {
      // plugin with same filename already installed
      FileUtils.deleteQuietly(jar);
      return null;
    }
    try {
      FileUtils.moveFileToDirectory(jar, destDir, true);
      return destFile;

    } catch (IOException e) {
      LOG.error("Fail to move the downloaded file: " + jar.getAbsolutePath(), e);
      return null;
    }
  }

  private void loadCorePlugins() throws IOException {
    for (File file : fileSystem.getCorePlugins()) {
      registerPlugin(file, true, false);
    }
  }

  public void uninstall(String pluginKey) {
    PluginMetadata metadata = pluginByKeys.get(pluginKey);
    if (metadata != null && !metadata.isCore()) {
      try {
        File masterFile = new File(fileSystem.getUserPluginsDir(), metadata.getFile().getName());
        FileUtils.moveFileToDirectory(masterFile, fileSystem.getRemovedPluginsDir(), true);
      } catch (IOException e) {
        throw new SonarException("Fail to uninstall plugin: " + pluginKey, e);
      }
    }
  }

  public List<String> getUninstalls() {
    List<String> names = Lists.newArrayList();
    if (fileSystem.getRemovedPluginsDir().exists()) {
      List<File> files = (List<File>) FileUtils.listFiles(fileSystem.getRemovedPluginsDir(), new String[]{"jar"}, false);
      for (File file : files) {
        names.add(file.getName());
      }
    }
    return names;
  }

  public void cancelUninstalls() {
    if (fileSystem.getRemovedPluginsDir().exists()) {
      List<File> files = (List<File>) FileUtils.listFiles(fileSystem.getRemovedPluginsDir(), new String[]{"jar"}, false);
      for (File file : files) {
        try {
          FileUtils.moveFileToDirectory(file, fileSystem.getUserPluginsDir(), false);
        } catch (IOException e) {
          throw new SonarException("Fail to cancel plugin uninstalls", e);
        }
      }
    }
  }

  private void deployPlugins() {
    for (PluginMetadata metadata : pluginByKeys.values()) {
      deploy((DefaultPluginMetadata) metadata);
    }
  }

  private void deploy(DefaultPluginMetadata plugin) {
    try {
      LOG.debug("Deploy plugin " + plugin);

      File pluginDeployDir = new File(fileSystem.getDeployedPluginsDir(), plugin.getKey());
      FileUtils.forceMkdir(pluginDeployDir);
      FileUtils.cleanDirectory(pluginDeployDir);

      List<File> deprecatedExtensions = fileSystem.getExtensions(plugin.getKey());
      for (File deprecatedExtension : deprecatedExtensions) {
        plugin.addDeprecatedExtension(deprecatedExtension);
      }

      installer.install(plugin, pluginDeployDir);

    } catch (IOException e) {
      throw new RuntimeException("Fail to deploy the plugin " + plugin, e);
    }
  }

  public Collection<PluginMetadata> getMetadata() {
    return pluginByKeys.values();
  }

  public PluginMetadata getMetadata(String pluginKey) {
    return pluginByKeys.get(pluginKey);
  }
}
