/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.neo4j.dbms.database;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.DATABASE_LABEL;
import static org.neo4j.kernel.database.NamedDatabaseId.NAMED_SYSTEM_DATABASE_ID;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.DozerDbSettings;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.api.DatabaseExistsException;
import org.neo4j.dbms.api.DatabaseManagementException;
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.factory.module.GlobalModule;
import org.neo4j.kernel.database.Database;
import org.neo4j.kernel.database.DatabaseIdFactory;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.logging.Log;

/**
 * The `MultiDatabaseManager` class is responsible for managing the lifecycle of multiple databases within the Neo4j instance.
 * It offers methods for creating, starting, stopping, and listing databases.
 *
 * Key Responsibilities:
 * - Creating a database: A synchronized method `createDatabase` is responsible for creating a new database context
 *   and adding it to the database repository. It also checks if the limit of databases is exceeded.
 * - Starting a database: Offers overloaded methods `startDatabase` to start a database by either named database ID or context.
 * - Stopping a database: Similar to starting, it provides methods to stop a database using different overloads.
 * - Listing all named database IDs: `listAllNamedDatabaseIds` method retrieves all database IDs, performing this action within a transaction.
 * - Logging: Throughout its operations, it logs informative and error messages to assist in tracking the behavior and potential issues.
 *
 * TODO notes indicate potential future modifications such as implementing a method to drop a database, checking if a database is already started,
 * removing the database limit check if needed, and managing the actual count of databases from the graph configuration.
 *
 * Exception Handling:
 * - Errors during the database operations like starting, stopping, or listing are logged with appropriate messages.
 * - Exceptions like `DatabaseExistsException` and `DatabaseManagementException` are used to handle specific error conditions.
 *
 * Dependencies:
 * - Uses `DatabaseRepository` for interacting with databases and managing their contexts.
 * - Utilizes `DatabaseContextFactory` for creating standalone database contexts.
 * - Utilizes Neo4j's `GlobalModule` for logging and other global dependencies like the `DatabaseOperationCounts.Counter`.
 *
 * Note: Certain methods and functionality are marked as TODO indicating pending implementations or decisions.
 */
public final class MultiDatabaseManager {

    private final DatabaseRepository<StandaloneDatabaseContext> databaseRepository;

    private final DatabaseContextFactory<StandaloneDatabaseContext, Optional<?>> databaseContextFactory;
    private final Log log;

    private final DatabaseOperationCounts.Counter counter;

    private final Config config;

    public MultiDatabaseManager(
            GlobalModule globalModule,
            DatabaseRepository<StandaloneDatabaseContext> databaseRepository,
            DatabaseContextFactory<StandaloneDatabaseContext, Optional<?>> databaseContextFactory) {
        this.log = globalModule.getLogService().getInternalLogProvider().getLog(this.getClass());
        this.databaseRepository = databaseRepository;
        this.databaseContextFactory = databaseContextFactory;
        this.counter = globalModule.getGlobalDependencies().resolveDependency(DatabaseOperationCounts.Counter.class);
        this.config = globalModule.getGlobalConfig();
    }

    /**
     * Create database with specified name.
     * Database name should be unique.
     * By default a database is in a started state when it is initially created.
     *
     * @param namedDatabaseId ID of database to create
     * @return database context for newly created database
     * @throws DatabaseExistsException In case if database with specified name already exists
     */
    // @Override
    public synchronized StandaloneDatabaseContext createDatabase(NamedDatabaseId namedDatabaseId) {
        requireNonNull(namedDatabaseId);
        log.info("Creating '%s'.", namedDatabaseId);
        checkDatabaseLimit(namedDatabaseId);
        StandaloneDatabaseContext databaseContext = databaseContextFactory.create(namedDatabaseId, Optional.empty());
        databaseRepository.add(namedDatabaseId, databaseContext);
        return databaseContext;
    }

    public void dropDatabase(NamedDatabaseId namedDatabaseId) {
        // Ensure the database exists
        Optional<StandaloneDatabaseContext> contextOptional = databaseRepository.getDatabaseContext(namedDatabaseId);

        if (contextOptional.isEmpty()) {
            log.warn("Database '%s' does not exist and cannot be dropped.", namedDatabaseId.name());
            return;
        }

        StandaloneDatabaseContext context = contextOptional.get();

        // Stop the database before dropping it
        try {
            log.info("Stopping database '%s' before dropping.", namedDatabaseId.name());
            // context.database().stop();
            this.stopDatabase(context);
        } catch (Exception e) {
            log.error(
                    "Failed to stop database '%s' before dropping. Error: %s", namedDatabaseId.name(), e.getMessage());
            throw new DatabaseManagementException("Failed to stop the database before dropping.", e);
        }

        // Drop the database from the system
        try {
            log.info("Dropping database '%s'.", namedDatabaseId.name());
            databaseRepository.remove(namedDatabaseId); // Remove it from repository
            context.database().prepareToDrop();

            // TODO: In the future we can backup the specific database before dropping it.
            // backupBeforeDelete(namedDatabaseId.name());

            context.database().drop();

            log.info("Dropped database '%s' successfully.", namedDatabaseId.name());

        } catch (Exception e) {
            log.error("Failed to drop database '%s'. Error: %s", namedDatabaseId.name(), e.getMessage());
            throw new DatabaseManagementException("Failed to drop the database.", e);
        }
    }

    /**
     * Copies database before dropping it.
     * @param dbName
     * @throws IOException
     */
    private void backupBeforeDelete(String dbName) throws IOException {
        // Get the Neo4j data directory from the configuration
        String dataDirectory = config.get(GraphDatabaseSettings.data_directory).toString();

        // Get current timestamp
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

        // Paths for the current and deleted database directories with timestamp
        Path dbSourcePath = Paths.get(dataDirectory, "databases", dbName);
        Path txSourcePath = Paths.get(dataDirectory, "transactions", dbName);

        Path dbDeletedPath = Paths.get(dataDirectory, "deleted", "databases", dbName + "-" + timestamp);
        Path txDeletedPath = Paths.get(dataDirectory, "deleted", "transactions", dbName + "-" + timestamp);

        // Ensure the deleted directories exist or create them
        try {
            Files.createDirectories(dbDeletedPath.getParent());
            Files.createDirectories(txDeletedPath.getParent());

            // Move the database folder with timestamp
            if (Files.exists(dbSourcePath)) {

                log.info("Moving database directory: %s to %s", dbSourcePath.toString(), dbDeletedPath.toString());
                Files.copy(dbSourcePath, dbDeletedPath, StandardCopyOption.REPLACE_EXISTING);
            } else {

                log.warn("Database directory does not exist: %s", dbSourcePath.toString());
            }

            // Move the transaction folder with timestamp
            if (Files.exists(txSourcePath)) {
                log.info("Moving transaction directory: %s to %s", txSourcePath.toString(), txDeletedPath.toString());
                Files.copy(txSourcePath, txDeletedPath, StandardCopyOption.REPLACE_EXISTING);
            } else {
                log.warn("Transaction directory does not exist: %s", txSourcePath.toString());
            }

            log.info(
                    "Database and transaction directories for '%s' moved to deleted with timestamp successfully.",
                    dbName);

        } catch (IOException e) {
            log.error("Failed to move database directories for '%s'. Error: %s", dbName, e.getMessage());
            throw e;
        }
    }

    public void startDatabase(NamedDatabaseId namedDatabaseId) {
        try {

            Optional<StandaloneDatabaseContext> contextOptional =
                    databaseRepository.getDatabaseContext(namedDatabaseId);

            this.startDatabase(contextOptional.get());

        } catch (Throwable t) {
            log.error("Failed to start " + namedDatabaseId, t);
        }
    }

    public void startDatabase(StandaloneDatabaseContext context) {
        var namedDatabaseId = context.database().getNamedDatabaseId();
        try {
            log.info("Starting '%s'.", namedDatabaseId);
            Database database = context.database();
            database.start();
            this.counter.increaseStartCount();
        } catch (Throwable t) {

            context.fail(UnableToStartDatabaseException.unableToStartDb(namedDatabaseId, t));
        }
    }

    public void stopDatabase(StandaloneDatabaseContext context) {
        var namedDatabaseId = context.database().getNamedDatabaseId();
        // Make sure that any failure (typically database panic) that happened until now is not interpreted as shutdown
        // failure
        context.clearFailure();
        try {
            log.info("Stopping '%s'.", namedDatabaseId);
            Database database = context.database();

            database.stop();
            log.info("Stopped '%s' successfully.", namedDatabaseId);
            this.counter.increaseStopCount();
        } catch (Throwable t) {
            log.error("Failed to stop " + namedDatabaseId, t);
            context.fail(new DatabaseManagementException(
                    format("An error occurred! Unable to stop `%s`.", namedDatabaseId), t));
        }
    }

    public void stopDatabase(NamedDatabaseId namedDatabaseId) {
        Optional<StandaloneDatabaseContext> contextOptional = databaseRepository.getDatabaseContext(namedDatabaseId);

        if (contextOptional.isEmpty()) {
            log.warn("Database '%s' does not exist and cannot be stopped.", namedDatabaseId.name());
            return;
        }

        StandaloneDatabaseContext context = contextOptional.get();

        this.stopDatabase(context);
    }

    private void checkDatabaseLimit(NamedDatabaseId namedDatabaseId) {

        // Default to 100 if the max databases is not set in the configuration.
        Integer maxDatabases =
                Optional.ofNullable(config.get(DozerDbSettings.max_databases)).orElse(100);
        if (databaseRepository.registeredDatabases().size() >= maxDatabases) {
            throw new DatabaseManagementException("Could not create gdb: " + namedDatabaseId.name()
                    + " because you have exceeded the limit of " + maxDatabases + ".");
        }
    }

    /**
     * Converts a given Node object into a NamedDatabaseId.
     *
     * This method extracts the "name" and "uuid" properties from the provided Node,
     * using them to create a NamedDatabaseId object. The "name" property is expected
     * to be a string representing the database name, and the "uuid" property should
     * be a string representation of the corresponding UUID.
     *
     * @param node The Node object containing the database properties.
     * @return A NamedDatabaseId object created from the extracted name and UUID.
     * @throws IllegalArgumentException If the properties "name" or "uuid" are not found or if the "uuid" property is not a valid UUID.
     */
    public NamedDatabaseId namedDatabaseIdFromNode(Node node) {

        String gdbName = (String) node.getProperty("name");
        UUID gdbUuid = UUID.fromString((String) node.getProperty("uuid"));
        return DatabaseIdFactory.from(gdbName, gdbUuid);
    }

    /**
     * Retrieves a set of all named database IDs within the system.
     *
     * This method begins a transaction with the system database and retrieves all the nodes
     * with the label defined by DATABASE_LABEL. It then maps each node to a NamedDatabaseId
     * object using the namedDatabaseIdFromNode method and collects them into a set.
     *
     * If any exceptions occur during this process, an error message is logged, but the
     * exception is not propagated further. In case of an exception, the method returns null.
     *
     * @return A set of NamedDatabaseId objects representing all named database IDs in the
     *         system, or null if an exception occurred.
     */
    public Set<NamedDatabaseId> listAllNamedDatabaseIds() {
        return listAllNamedDatabaseIds(null);
    }

    public Set<NamedDatabaseId> listAllNamedDatabaseIds(TopologyGraphDbmsModel.DatabaseStatus databaseStatus) {
        Set<NamedDatabaseId> namedDatabaseIds = new HashSet<>();

        try (var transaction = this.databaseRepository
                .getDatabaseContext(NAMED_SYSTEM_DATABASE_ID)
                .orElseThrow()
                .databaseFacade()
                .beginTx()) {

            // Retrieve all nodes with the DATABASE_LABEL
            var nodeStream = transaction.findNodes(DATABASE_LABEL).stream();

            // Process each node and retrieve the status directly from node properties
            nodeStream.forEach(node -> {
                NamedDatabaseId dbId = namedDatabaseIdFromNode(node);

                // Assuming status is stored as a property on the node, adjust the property key as needed
                String nodeStatus =
                        (String) node.getProperty("status", "UNKNOWN"); // Default to "UNKNOWN" if status is absent

                log.info("Database Name: " + dbId.name() + ", Status: " + nodeStatus);

                // Check if the status matches the specified `databaseStatus`, or add all if null
                if (databaseStatus == null || databaseStatus.statusName().equals(nodeStatus)) {
                    namedDatabaseIds.add(dbId);
                }
            });

        } catch (Exception e) {
            log.error("An error occurred trying to list all the databases. Error:", e);
        }

        log.info("Returning Named Database IDs: " + namedDatabaseIds);
        return namedDatabaseIds;
    }

    private TopologyGraphDbmsModel.DatabaseStatus getDatabaseStatus(Node node) {
        String status = (String) node.getProperty("status", null);
        return status != null ? TopologyGraphDbmsModel.DatabaseStatus.valueOf(status) : null;
    }
}
