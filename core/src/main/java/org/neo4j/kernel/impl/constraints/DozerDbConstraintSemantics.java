/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/*
 *  Modifications Copyright (c) DozerDB
 *  https://dozerdb.org
 */
package org.neo4j.kernel.impl.constraints;

import org.neo4j.annotations.service.ServiceProvider;
import org.neo4j.common.TokenNameLookup;
import org.neo4j.internal.kernel.api.*;
import org.neo4j.internal.kernel.api.exceptions.schema.ConstraintValidationException;
import org.neo4j.internal.kernel.api.exceptions.schema.CreateConstraintFailureException;
import org.neo4j.internal.schema.ConstraintDescriptor;
import org.neo4j.internal.schema.LabelSchemaDescriptor;
import org.neo4j.internal.schema.RelationTypeSchemaDescriptor;
import org.neo4j.internal.schema.SchemaDescriptor;
import org.neo4j.internal.schema.constraints.*;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.api.exceptions.schema.NodePropertyExistenceException;
import org.neo4j.kernel.api.exceptions.schema.PropertyTypeException;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.storageengine.api.PropertySelection;
import org.neo4j.storageengine.api.StandardConstraintRuleAccessor;
import org.neo4j.storageengine.api.StorageReader;
import org.neo4j.storageengine.api.txstate.ReadableTransactionState;
import org.neo4j.storageengine.api.txstate.TxStateVisitor;
import org.neo4j.values.storable.Value;

@ServiceProvider
public class DozerDbConstraintSemantics extends StandardConstraintSemantics {

    protected final StandardConstraintRuleAccessor standardConstraintRuleAccessor =
            new StandardConstraintRuleAccessor();

    public DozerDbConstraintSemantics() {
        this(1);
    }

    protected DozerDbConstraintSemantics(int priority) {
        super(priority);
    }

    @Override
    public String getName() {
        return "dozerDbConstraints";
    }

    @Override
    public void assertKeyConstraintAllowed(SchemaDescriptor descriptor) throws CreateConstraintFailureException {
        // We don't need to do anything here.
    }

    @Override
    public void validateNodeKeyConstraint(
            NodeLabelIndexCursor allNodes,
            NodeCursor nodeCursor,
            PropertyCursor propertyCursor,
            LabelSchemaDescriptor descriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        // TODO: Implement
    }

    @Override
    public void validateRelKeyConstraint(
            RelationshipTypeIndexCursor allRelationships,
            RelationshipScanCursor relCursor,
            PropertyCursor propertyCursor,
            RelationTypeSchemaDescriptor descriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        // TODO: Implement
    }

    @Override
    public void validateNodePropertyExistenceConstraint(
            NodeLabelIndexCursor allNodes,
            NodeCursor nodeCursor,
            PropertyCursor propertyCursor,
            LabelSchemaDescriptor descriptor,
            TokenNameLookup tokenNameLookup,
            boolean isDependent)
            throws CreateConstraintFailureException {

        // TODO: We need to loop through the cursor and check each one for property constraints using property ids in
        // LabelSchemaDescriptor.
        // TODO: For each node we need to pass to validateNodePropertyExistenceConstraint
        // TODO: Implement

    }

    @Override
    public void validateRelationshipPropertyExistenceConstraint(
            RelationshipScanCursor relationshipCursor,
            PropertyCursor propertyCursor,
            RelationTypeSchemaDescriptor descriptor,
            TokenNameLookup tokenNameLookup,
            boolean isDependent)
            throws CreateConstraintFailureException {
        // TODO: Implement
    }

    @Override
    public void validateRelationshipPropertyExistenceConstraint(
            RelationshipTypeIndexCursor allRelationships,
            PropertyCursor propertyCursor,
            RelationTypeSchemaDescriptor descriptor,
            TokenNameLookup tokenNameLookup,
            boolean isDependent)
            throws CreateConstraintFailureException {
        // TODO: Implement
    }

    @Override
    public ConstraintDescriptor createUniquenessConstraintRule(
            long ruleId, UniquenessConstraintDescriptor descriptor, long indexId) {
        return standardConstraintRuleAccessor.createUniquenessConstraintRule(ruleId, descriptor, indexId);
    }

    @Override
    public ConstraintDescriptor createKeyConstraintRule(long ruleId, KeyConstraintDescriptor descriptor, long indexId)
            throws CreateConstraintFailureException {
        return this.standardConstraintRuleAccessor.createKeyConstraintRule(ruleId, descriptor, indexId);
    }

    @Override
    public ConstraintDescriptor createExistenceConstraint(long ruleId, ConstraintDescriptor descriptor)
            throws CreateConstraintFailureException {
        return this.standardConstraintRuleAccessor.createExistenceConstraint(ruleId, descriptor);
    }

    @Override
    public ConstraintDescriptor createPropertyTypeConstraint(long ruleId, TypeConstraintDescriptor descriptor)
            throws CreateConstraintFailureException {
        return this.standardConstraintRuleAccessor.createPropertyTypeConstraint(ruleId, descriptor);
    }

    @Override
    public ConstraintDescriptor createRelationshipEndpointLabelConstraint(
            long ruleId, RelationshipEndpointLabelConstraintDescriptor descriptor)
            throws CreateConstraintFailureException {
        return this.standardConstraintRuleAccessor.createRelationshipEndpointLabelConstraint(ruleId, descriptor);
    }

    @Override
    public ConstraintDescriptor createNodeLabelExistenceConstraint(
            long ruleId, NodeLabelExistenceConstraintDescriptor descriptor) throws CreateConstraintFailureException {
        return this.standardConstraintRuleAccessor.createNodeLabelExistenceConstraint(ruleId, descriptor);
    }

    @Override
    public TxStateVisitor decorateTxStateVisitor(
            StorageReader storageReader,
            Read read,
            CursorFactory cursorFactory,
            ReadableTransactionState readableTransactionState,
            TxStateVisitor txStateVisitor,
            CursorContext cursorContext,
            MemoryTracker memoryTracker) {
        return !readableTransactionState.hasDataChanges()
                ? txStateVisitor
                : ConstraintChecker.constraintChecker(storageReader)
                        .visit(cursorContext, cursorFactory, memoryTracker, read, txStateVisitor);
    }

    @Override
    public void validateNodePropertyExistenceConstraint(
            NodeCursor nodeCursor,
            PropertyCursor propertyCursor,
            LabelSchemaDescriptor schema,
            TokenNameLookup tokenNameLookup,
            boolean isDependent)
            throws CreateConstraintFailureException {
        while (nodeCursor.next()) {
            if (this.verify(schema.getPropertyIds(), propertyCursor, nodeCursor)) {
                continue;
            }

            ConstraintDescriptor constraintDescriptor = ConstraintDescriptorFactory.existsForSchema(schema, false);

            NodePropertyExistenceException nodePropertyExistenceException =
                    NodePropertyExistenceException.propertyPresenceViolation(
                            schema,
                            tokenNameLookup,
                            constraintDescriptor,
                            ConstraintValidationException.Phase.VERIFICATION,
                            nodeCursor.nodeReference());
            throw new CreateConstraintFailureException(
                    nodePropertyExistenceException.constraint(), nodePropertyExistenceException);
        }
    }
    /**
     * Verifies if all specified properties exist in the given entity.
     * <p>
     * This method checks the presence of each property ID in the {@code propertyIds} array
     * within the entity represented by {@code EntityCursor}. It iterates through the entity's
     * properties using {@code PropertyCursor} and decrements a counter for each matched property.
     * </p>
     *
     * <p>
     * If all properties specified in {@code propertyIds} are found, the method returns {@code true}.
     * Otherwise, if one or more properties are missing, it returns {@code false}.
     * </p>
     *
     * @param propertyIds     an array of property IDs to verify in the entity
     * @param propertyCursor  the {@code PropertyCursor} used to iterate over the entity's properties
     * @param entityCursor    the {@code EntityCursor} representing the entity to be checked
     *
     * @return {@code true} if all properties specified in {@code propertyIds} are present in the entity;
     *         {@code false} otherwise
     */
    private boolean verify(int[] propertyIds, PropertyCursor propertyCursor, EntityCursor entityCursor) {

        entityCursor.properties(propertyCursor, PropertySelection.onlyKeysSelection(propertyIds));

        int propCount = propertyIds.length;
        while (propertyCursor.next()) propCount--;

        return propCount == 0;
    }

    @Override
    public void validateNodeKeyConstraint(
            NodeCursor nodeCursor,
            PropertyCursor propertyCursor,
            LabelSchemaDescriptor descriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        validateNodePropertyExistenceConstraint(nodeCursor, propertyCursor, descriptor, tokenNameLookup, false);
    }

    @Override
    public void validateRelKeyConstraint(
            RelationshipScanCursor relCursor,
            PropertyCursor propertyCursor,
            RelationTypeSchemaDescriptor descriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        validateRelationshipPropertyExistenceConstraint(relCursor, propertyCursor, descriptor, tokenNameLookup, false);
    }

    @Override
    public void validateNodePropertyTypeConstraint(
            NodeCursor nodeCursor,
            PropertyCursor propertyCursor,
            TypeConstraintDescriptor descriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        while (nodeCursor.next()) {
            this.validateConstraint(descriptor, nodeCursor, propertyCursor, tokenNameLookup);
        }
    }

    @Override
    public void validateNodePropertyTypeConstraint(
            NodeLabelIndexCursor allNodes,
            NodeCursor nodeCursor,
            PropertyCursor propertyCursor,
            TypeConstraintDescriptor descriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        while (allNodes.next()) {
            allNodes.node(nodeCursor);
            this.validateNodePropertyTypeConstraint(nodeCursor, propertyCursor, descriptor, tokenNameLookup);
        }
    }

    @Override
    public void validateRelationshipPropertyTypeConstraint(
            RelationshipScanCursor relationshipCursor,
            PropertyCursor propertyCursor,
            TypeConstraintDescriptor descriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        while (relationshipCursor.next()) {
            this.validateConstraint(descriptor, relationshipCursor, propertyCursor, tokenNameLookup);
        }
    }

    @Override
    public void validateRelationshipPropertyTypeConstraint(
            RelationshipTypeIndexCursor allRelationships,
            PropertyCursor propertyCursor,
            TypeConstraintDescriptor descriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        while (allRelationships.next()) {
            if (allRelationships.readFromStore()) {
                this.validateConstraint(descriptor, allRelationships, propertyCursor, tokenNameLookup);
            }
        }
    }

    /**
     * Validates a property type constraint for a given entity.
     * <p>
     * This method checks if the properties associated with the specified {@code EntityCursor}
     * meet the type requirements specified by the {@code TypeConstraintDescriptor}. It fetches
     * the property using the {@code PropertyCursor} and verifies its value against the expected
     * type defined in the constraint descriptor.
     * </p>
     *
     * <p>
     * If the property value does not satisfy the required type constraints, a
     * {@code CreateConstraintFailureException} is thrown, encapsulating a
     * {@code PropertyTypeException} that describes the specific constraint violation.
     * </p>
     *
     * @param descriptor      the {@code TypeConstraintDescriptor} defining the expected property type constraint
     * @param entityCursor    the {@code EntityCursor} representing the entity to be validated
     * @param propertyCursor  the {@code PropertyCursor} used to access the entity's property values
     * @param tokenNameLookup a lookup tool to convert internal token IDs to user-friendly names
     *
     * @throws CreateConstraintFailureException if the property type constraint is violated
     */
    private void validateConstraint(
            TypeConstraintDescriptor descriptor,
            EntityCursor entityCursor,
            PropertyCursor propertyCursor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        entityCursor.properties(
                propertyCursor, PropertySelection.selection(descriptor.schema().getPropertyId()));
        if (propertyCursor.next()) {
            Value value = propertyCursor.propertyValue();

            if (TypeRepresentation.disallows(descriptor.propertyType(), value)) {
                PropertyTypeException propertyTypeException = new PropertyTypeException(
                        descriptor,
                        ConstraintValidationException.Phase.VERIFICATION,
                        entityCursor.reference(),
                        tokenNameLookup,
                        value);
                throw new CreateConstraintFailureException(propertyTypeException.constraint(), propertyTypeException);
            }
        }
    }

    @Override
    public void validateRelationshipEndpointLabelConstraint(
            RelationshipScanCursor relCursor,
            NodeCursor nodeCursor,
            RelationshipEndpointLabelConstraintDescriptor descriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        // TODO: Implement
    }

    @Override
    public void validateNodeLabelExistenceConstraint(
            NodeLabelIndexCursor allNodes,
            NodeCursor nodeCursor,
            NodeLabelExistenceConstraintDescriptor descriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        // TODO: Implement
    }

    @Override
    public void validateNodeLabelExistenceConstraint(
            NodeCursor nodeCursor, NodeLabelExistenceConstraintDescriptor descriptor, TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        // TODO: Implement
    }
}
