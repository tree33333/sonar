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
package org.sonar.batch.bootstrap;

import org.junit.Test;
import org.sonar.api.config.Settings;
import org.sonar.api.platform.Server;
import org.sonar.batch.ServerMetadata;
import org.sonar.core.persistence.BadDatabaseVersion;
import org.sonar.core.persistence.DatabaseVersion;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DatabaseBatchCompatibilityTest {

  private Server server = new ServerMetadata(new Settings());

  @Test(expected = BadDatabaseVersion.class)
  public void shouldFailIfRequiresDowngrade() {
    DatabaseVersion version = mock(DatabaseVersion.class);
    when(version.getStatus()).thenReturn(DatabaseVersion.Status.REQUIRES_DOWNGRADE);
    new DatabaseBatchCompatibility(version, server).start();
  }

  @Test(expected = BadDatabaseVersion.class)
  public void shouldFailIfRequiresUpgrade() {
    DatabaseVersion version = mock(DatabaseVersion.class);
    when(version.getStatus()).thenReturn(DatabaseVersion.Status.REQUIRES_UPGRADE);
    new DatabaseBatchCompatibility(version, server).start();
  }

  @Test
  public void shouldDoNothingIfUpToDate() {
    DatabaseVersion version = mock(DatabaseVersion.class);
    when(version.getStatus()).thenReturn(DatabaseVersion.Status.UP_TO_DATE);
    new DatabaseBatchCompatibility(version, server).start();
    // no error
  }
}
