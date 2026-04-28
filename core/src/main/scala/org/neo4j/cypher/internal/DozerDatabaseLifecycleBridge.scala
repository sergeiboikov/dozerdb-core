/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal

import org.neo4j.common.DependencyResolver
import org.neo4j.dbms.database.DatabaseContextFactory
import org.neo4j.dbms.database.DatabaseRepository
import org.neo4j.dbms.database.StandaloneDatabaseContext
import org.neo4j.kernel.database.DatabaseIdFactory
import org.neo4j.kernel.database.NamedDatabaseId

import java.util.UUID

import scala.jdk.CollectionConverters.MapHasAsScala
import scala.jdk.OptionConverters.RichOptional

/**
 * Bridges system-graph admin commands with runtime lifecycle.
 * Dozer CREATE/START/STOP/DROP update the system graph explicitly; this bridge
 * keeps the in-memory repository in sync so SHOW DATABASES returns runtime state.
 */
final class DozerDatabaseLifecycleBridge(resolver: DependencyResolver) {

  private val repository =
    resolver.resolveDependency(
      classOf[DatabaseRepository[_]]
    ).asInstanceOf[DatabaseRepository[StandaloneDatabaseContext]]

  private val contextFactory =
    resolver.resolveDependency(classOf[DatabaseContextFactory[?, ?]])
      .asInstanceOf[DatabaseContextFactory[StandaloneDatabaseContext, NamedDatabaseId]]

  def createAndStart(name: String, uuid: String): Unit = synchronized {
    val context = findContextByName(name).getOrElse {
      val namedDatabaseId = DatabaseIdFactory.from(name, UUID.fromString(uuid))
      val createdContext = contextFactory.create(namedDatabaseId)
      repository.add(namedDatabaseId, createdContext)
      createdContext
    }
    startContext(name, context)
  }

  def start(name: String): Unit = synchronized {
    val context = findContextByName(name).orElse(resolveContextFromRepositoryId(name)).getOrElse {
      throw new IllegalStateException(s"Database '$name' exists in topology but runtime context was not resolved.")
    }
    startContext(name, context)
  }

  def stop(name: String): Unit = synchronized {
    findEntryByName(name).foreach { case (_, context) =>
      try {
        context.clearFailure()
        if (context.database().isStarted()) {
          context.database().stop()
        }
      } catch {
        case error: Throwable =>
          throw new IllegalStateException(s"Could not stop runtime context for database '$name'.", error)
      }
    }
  }

  def drop(name: String): Unit = synchronized {
    findEntryByName(name).foreach { case (namedDatabaseId, context) =>
      try {
        context.clearFailure()
        if (context.database().isStarted()) {
          context.database().stop()
        }
      } catch {
        case error: Throwable =>
          throw new IllegalStateException(s"Could not stop runtime context for dropped database '$name'.", error)
      }
      repository.remove(namedDatabaseId)
    }
  }

  private def resolveContextFromRepositoryId(name: String): Option[StandaloneDatabaseContext] = {
    repository.databaseIdRepository().getByName(name).toScala.map { namedDatabaseId =>
      repository.getDatabaseContext(namedDatabaseId).toScala.getOrElse {
        val createdContext = contextFactory.create(namedDatabaseId)
        repository.add(namedDatabaseId, createdContext)
        createdContext
      }
    }
  }

  private def findContextByName(name: String): Option[StandaloneDatabaseContext] =
    findEntryByName(name).map(_._2)

  private def findEntryByName(name: String): Option[(NamedDatabaseId, StandaloneDatabaseContext)] =
    repository.registeredDatabases().asScala.collectFirst {
      case (namedDatabaseId, context) if namedDatabaseId.name() == name => (namedDatabaseId, context)
    }

  private def startContext(name: String, context: StandaloneDatabaseContext): Unit = {
    try {
      if (!context.database().isStarted()) {
        context.database().start()
      }
    } catch {
      case error: Throwable =>
        throw new IllegalStateException(s"Could not start runtime context for database '$name'.", error)
    }
  }
}
