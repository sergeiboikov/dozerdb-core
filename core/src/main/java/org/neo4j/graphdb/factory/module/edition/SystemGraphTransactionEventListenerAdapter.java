/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *
 *
 */
package org.neo4j.graphdb.factory.module.edition;

import static org.neo4j.configuration.GraphDatabaseSettings.initial_default_database;
import static org.neo4j.kernel.database.NamedDatabaseId.NAMED_SYSTEM_DATABASE_ID;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.neo4j.configuration.Config;
import org.neo4j.dbms.database.MultiDatabaseManager;
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.event.TransactionData;
import org.neo4j.graphdb.event.TransactionEventListenerAdapter;
import org.neo4j.graphdb.factory.module.GlobalModule;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.logging.InternalLog;

/**
 * The SystemGraphTransactionEventListenerAdapter class extends the TransactionEventListenerAdapter
 * and overrides its methods to handle the transaction events in the context of the system graph database.
 * This class is used to initialize and manage other databases besides the system and default ones.
 * <p>
 * This class has the ability to retrieve the id of a named database,
 * and to handle the updates after a commit in a system database is performed.
 * <p>
 * The key methods of this class are:
 * <p>
 * - getNamedDatabaseIdForName(String nameToFind): Retrieves the id of a database with a specified name.
 * - afterCommit(TransactionData txData, Object state, GraphDatabaseService systemDatabase): Handles changes
 * in the system database after a commit has been performed. Specifically, it checks the status and name of
 * the assigned node properties. If these properties meet certain conditions, it retrieves the named database id
 * associated with the name, creates the database if it exists, and starts the database.
 * <p>
 * This class also contains a private final instance of DatabaseManager, which is used to handle database operations.
 */
public class SystemGraphTransactionEventListenerAdapter extends TransactionEventListenerAdapter<Object> {

    protected final Config config;
    private final MultiDatabaseManager databaseManager;
    private final List<String> gdbsToIgnore;

    private final InternalLog log;

    /**
     * Constructor for the SystemGraphTransactionEventListenerAdapter class.
     *
     * @param multiDatabaseManager the MultiDatabaseManager instance
     * @param globalModule         the GlobalModule instance
     */
    public SystemGraphTransactionEventListenerAdapter(
            MultiDatabaseManager multiDatabaseManager, GlobalModule globalModule) {

        this.databaseManager = multiDatabaseManager;

        this.config = globalModule.getGlobalConfig();

        log = globalModule.getLogService().getInternalLogProvider().getLog(this.getClass());
        String defaultDatabaseName = config.get(initial_default_database);
        String systemDatabaseName = NAMED_SYSTEM_DATABASE_ID.name();

        gdbsToIgnore = List.of(defaultDatabaseName, systemDatabaseName);
    }

    /**
     * Retrieves the id of a database with a specified name.
     *
     * @param nameToFind the name of the database to find
     * @return the id of the database with the specified name
     */
    public NamedDatabaseId getNamedDatabaseIdForName(String nameToFind) {

        return this.databaseManager.listAllNamedDatabaseIds().stream()
                .filter(namedDatabaseId -> namedDatabaseId.name().equals(nameToFind))
                .findFirst()
                .orElse(null);
    } // End

    /**
     * We handle dropping database and starting and stopping here.
     *
     * @param txData          the changes that will be committed in this transaction.
     * @param transaction     ongoing transaction
     * @param databaseService underlying database service
     * @return
     * @throws Exception
     */
    @Override
    public Object beforeCommit(TransactionData txData, Transaction transaction, GraphDatabaseService databaseService)
            throws Exception {

        AtomicReference<String> newStatus = new AtomicReference<>();
        AtomicReference<String> oldStatus = new AtomicReference<>();
        AtomicReference<String> name = new AtomicReference<>();
        AtomicReference<Boolean> deleteAction = new AtomicReference<>(false);

        // If we have assignedNodeProperties - we are looking for a status change and will handle it.
        txData.assignedNodeProperties().forEach(nodePropertyEntry -> {
            if (nodePropertyEntry.key().equals("status")) {

                newStatus.set(nodePropertyEntry.value().toString());

                if (nodePropertyEntry.previouslyCommittedValue() != null) {
                    oldStatus.set(nodePropertyEntry.previouslyCommittedValue().toString());
                }
            } // End if.

            if (nodePropertyEntry.key().equals("name")) {
                name.set(nodePropertyEntry.value().toString());
            }

            if (name.get() == null) {
                try {
                    name.set(fetchDatabaseName(nodePropertyEntry.entity()));

                } catch (Exception e) {
                    // log.warn(" Exception trying to fetch database name: " + e.getMessage());
                }
            }
        });

        if (oldStatus.get() != null
                && newStatus.get() != null
                && !newStatus.get().equals(oldStatus.get())
                && name.get() != null) {
            NamedDatabaseId nId = getNamedDatabaseIdForName(name.get());
            if (nId != null) {
                handleStatusChange(newStatus.get(), nId);
            } else {
                log.warn("Database " + name.get() + " was not found.");
            }
        }

        // Loop through the created nodes in the transaction
        txData.createdNodes().forEach(node -> {

            // Check if the node has the label DELETED_DATABASE_LABEL
            if (node.hasLabel(TopologyGraphDbmsModel.DELETED_DATABASE_LABEL)) {

                deleteAction.set(true); // Set delete action flag
                if (node.hasProperty("name")) {
                    name.set(node.getProperty("name").toString());
                }
            }
        });

        boolean shouldProcess = (name.get() != null && !gdbsToIgnore.contains(name.get()) && deleteAction.get());

        // Ignore if no relevant node found or if the transaction is for the system or default graph database.
        if (shouldProcess) {

            // Retrieve NamedDatabaseId for the database name
            NamedDatabaseId nId = getNamedDatabaseIdForName(name.get());

            if (nId != null) {

                // Drop the database if it's marked for deletion
                log.info(" Dropping database: " + name.get());
                databaseManager.dropDatabase(nId);

            } else {
                log.warn(" Database " + name.get() + " was not found.");
            }
        }

        return super.beforeCommit(txData, transaction, databaseService);
    }

    /**
     * We handle creating database here.
     *
     * @param txData         the changes that were committed in this transaction.
     * @param state          the object returned by
     *                       {@link #beforeCommit(TransactionData, Transaction, GraphDatabaseService)}.
     * @param systemDatabase underlying database service
     */
    @Override
    public void afterCommit(TransactionData txData, Object state, GraphDatabaseService systemDatabase) {

        AtomicReference<String> newStatus = new AtomicReference<>();
        AtomicReference<String> oldStatus = new AtomicReference<>();
        AtomicReference<String> name = new AtomicReference<>();

        txData.assignedNodeProperties().forEach(nodePropertyEntry -> {
            if (nodePropertyEntry.key().equals("status")) {

                newStatus.set(nodePropertyEntry.value().toString());

                if (nodePropertyEntry.previouslyCommittedValue() != null) {
                    oldStatus.set(nodePropertyEntry.previouslyCommittedValue().toString());
                }
            } // End if.

            if (nodePropertyEntry.key().equals("name")) {
                name.set(nodePropertyEntry.value().toString());
            }
        });

        // We ignore / return if the transaction is related to the system or default graph database.
        if (name.get() == null || gdbsToIgnore.contains(name.get())) {
            return;
        }

        if (shouldCreateDatabase(newStatus.get(), name.get())) {
            NamedDatabaseId nId = getNamedDatabaseIdForName(name.get());

            if (nId != null) {

                databaseManager.createDatabase(nId);
                databaseManager.startDatabase(nId);
                log.info(" Created and Started Database : " + name.get());
            } else {
                log.error(" NamedDatabaseID for name " + name.get() + " was not found. ");
            }
        }
    }

    // We want to implement better checks here in the future.  In the aftercommit we know that is the newStatus is set
    // and the name is provided, then it is a create, but
    // we should look at createdNodes and see if the node is there.  If it is not, then we should not create the
    // database.  We should also check if the database is already created.
    private boolean shouldCreateDatabase(String newStatus, String name) {
        return (newStatus != null && name != null);
    }

    // Helper method to fetch the database name based on the status change node
    private String fetchDatabaseName(Node node) {
        if (node.hasProperty("name")) {

            return node.getProperty("name").toString();
        } else {
            return null;
        }
    }

    // Method to handle status changes by starting or stopping the database as appropriate
    private void handleStatusChange(String newStatus, NamedDatabaseId namedDatabaseId) {

        switch (newStatus) {
            case "online":
                log.info("Starting database: " + namedDatabaseId.name());
                databaseManager.startDatabase(namedDatabaseId);
                break;
            case "offline":
                log.info("Stopping database: " + namedDatabaseId.name());
                databaseManager.stopDatabase(namedDatabaseId);
                break;
            default:
                log.warn("Unknown status: " + newStatus + " for database: " + namedDatabaseId.name());
        }
    }
}
