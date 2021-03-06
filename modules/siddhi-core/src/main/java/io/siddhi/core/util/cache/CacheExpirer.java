/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.siddhi.core.util.cache;

import io.siddhi.core.config.SiddhiAppContext;
import io.siddhi.core.config.SiddhiQueryContext;
import io.siddhi.core.event.ComplexEventChunk;
import io.siddhi.core.event.state.MetaStateEvent;
import io.siddhi.core.event.state.StateEvent;
import io.siddhi.core.event.stream.MetaStreamEvent;
import io.siddhi.core.event.stream.StreamEvent;
import io.siddhi.core.event.stream.StreamEventFactory;
import io.siddhi.core.exception.ConnectionUnavailableException;
import io.siddhi.core.exception.SiddhiAppRuntimeException;
import io.siddhi.core.executor.VariableExpressionExecutor;
import io.siddhi.core.table.CacheTable;
import io.siddhi.core.table.InMemoryTable;
import io.siddhi.core.table.Table;
import io.siddhi.core.table.record.AbstractQueryableRecordTable;
import io.siddhi.core.util.collection.operator.CompiledCondition;
import io.siddhi.core.util.collection.operator.MatchingMetaInfoHolder;
import io.siddhi.core.util.parser.MatcherParser;
import io.siddhi.query.api.definition.Attribute;
import io.siddhi.query.api.definition.TableDefinition;
import io.siddhi.query.api.expression.Expression;
import io.siddhi.query.api.expression.Variable;
import io.siddhi.query.api.expression.condition.Compare;
import io.siddhi.query.api.expression.constant.LongConstant;
import io.siddhi.query.api.expression.math.Subtract;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static io.siddhi.core.util.SiddhiConstants.CACHE_EXPIRE_CURRENT_TIME;
import static io.siddhi.core.util.SiddhiConstants.CACHE_TABLE_TIMESTAMP_ADDED;
import static io.siddhi.core.util.cache.CacheUtils.findEventChunkSize;

/**
 * this class has a runnable which runs on a separate thread called by AbstractQueryableRecordTable and handles cache
 * expiry
 */
public class CacheExpirer {
    private static final Logger log = Logger.getLogger(CacheExpirer.class);
    private InMemoryTable cacheTable;
    private StreamEventFactory streamEventFactory;
    private Map<String, Table> tableMap;
    private AbstractQueryableRecordTable storeTable;
    private SiddhiAppContext siddhiAppContext;
    private long retentionPeriod;
    private ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private CompiledCondition cacheExpiryCompiledCondition;

    public CacheExpirer(long retentionPeriod, InMemoryTable cacheTable, Map<String, Table> tableMap,
                        AbstractQueryableRecordTable storeTable, SiddhiAppContext siddhiAppContext) {
        this.cacheTable = cacheTable;
        this.tableMap = tableMap;
        this.storeTable = storeTable;
        this.siddhiAppContext = siddhiAppContext;
        this.retentionPeriod = retentionPeriod;
        this.cacheExpiryCompiledCondition = generateExpiryCompiledCondition();
    }

    private ComplexEventChunk<StateEvent> generateDeleteEventChunk() {
        ComplexEventChunk<StateEvent> deleteEventChunk = new ComplexEventChunk<>();
        StateEvent stateEvent = new StateEvent(2, 0);
        StreamEvent deletingEvent = streamEventFactory.newInstance();
        deletingEvent.setOutputData(new Object[]{siddhiAppContext.getTimestampGenerator().currentTime()});
        stateEvent.addEvent(0, deletingEvent);
        deleteEventChunk.add(stateEvent);
        return deleteEventChunk;
    }

    private CompiledCondition generateExpiryCompiledCondition() {
        MetaStreamEvent tableMetaStreamEvent = new MetaStreamEvent();
        tableMetaStreamEvent.setEventType(MetaStreamEvent.EventType.TABLE);
        TableDefinition matchingTableDefinition = TableDefinition.id(cacheTable.getTableDefinition().getId());
        for (Attribute attribute : cacheTable.getTableDefinition().getAttributeList()) {
            tableMetaStreamEvent.addOutputData(attribute);
            matchingTableDefinition.attribute(attribute.getName(), attribute.getType());
        }
        tableMetaStreamEvent.addInputDefinition(matchingTableDefinition);
        streamEventFactory = new StreamEventFactory(tableMetaStreamEvent);

        Variable rightExpressionForSubtract = new Variable(CACHE_TABLE_TIMESTAMP_ADDED);
        rightExpressionForSubtract.setStreamId(cacheTable.getTableDefinition().getId());
        Expression rightExpressionForCompare = new LongConstant(retentionPeriod);
        Compare.Operator greaterThanOperator = Compare.Operator.GREATER_THAN;

        MetaStreamEvent currentTimeMetaStreamEvent = new MetaStreamEvent();
        currentTimeMetaStreamEvent.setEventType(MetaStreamEvent.EventType.TABLE);
        Attribute currentTimeAttribute = new Attribute(CACHE_EXPIRE_CURRENT_TIME, Attribute.Type.LONG);
        currentTimeMetaStreamEvent.addOutputData(currentTimeAttribute);
        TableDefinition currentTimeTableDefinition = TableDefinition.id("");
        currentTimeTableDefinition.attribute(CACHE_EXPIRE_CURRENT_TIME, Attribute.Type.LONG);
        currentTimeMetaStreamEvent.addInputDefinition(currentTimeTableDefinition);

        MetaStateEvent metaStateEvent = new MetaStateEvent(2);
        metaStateEvent.addEvent(currentTimeMetaStreamEvent);
        metaStateEvent.addEvent(tableMetaStreamEvent);

        MatchingMetaInfoHolder matchingMetaInfoHolder =
                MatcherParser.constructMatchingMetaStateHolder(metaStateEvent, 0,
                        cacheTable.getTableDefinition(), 0);
        List<VariableExpressionExecutor> variableExpressionExecutors = new ArrayList<>();
        Expression leftExpressionForSubtract = new Variable(CACHE_EXPIRE_CURRENT_TIME);
        Expression leftExpressionForCompare = new Subtract(leftExpressionForSubtract, rightExpressionForSubtract);
        Expression deleteCondition = new Compare(leftExpressionForCompare, greaterThanOperator,
                rightExpressionForCompare);
        SiddhiQueryContext siddhiQueryContext = new SiddhiQueryContext(siddhiAppContext, "expiryDeleteQuery");
        return cacheTable.compileCondition(deleteCondition, matchingMetaInfoHolder, variableExpressionExecutors,
                tableMap, siddhiQueryContext);
    }

    private void handleCacheExpiry() {
        log.debug(siddhiAppContext.getName() + ": CacheExpirer started");
        StateEvent stateEventForCaching = new StateEvent(1, 0);
        StreamEvent loadedDataFromStore;

        if (storeTable.getStoreTableSize() != -1 && storeTable.getStoreSizeLastCheckedTime() >
                        siddhiAppContext.getTimestampGenerator().currentTime() - retentionPeriod * 10) {
            if (log.isDebugEnabled()) {
                log.debug(siddhiAppContext.getName() + ": checking size of store table");
            }
            try {
            if (storeTable.getStoreTableSize() <= storeTable.getMaxCacheSize()) {
                AbstractQueryableRecordTable.queryStoreWithoutCheckingCache.set(Boolean.TRUE);
                try {
                    if (storeTable.getCacheLastReloadTime() < siddhiAppContext.getTimestampGenerator().currentTime() +
                            retentionPeriod) {
                        loadedDataFromStore = storeTable.query(stateEventForCaching,
                                storeTable.getCompiledConditionForCaching(),
                                storeTable.getCompiledSelectionForCaching(), storeTable.
                                        getOutputAttributesForCaching());
                        clearCacheAndReload(loadedDataFromStore);
                        storeTable.setCacheLastReloadTime(siddhiAppContext.getTimestampGenerator().currentTime());
                    }
                } finally {
                    AbstractQueryableRecordTable.queryStoreWithoutCheckingCache.set(Boolean.FALSE);
                }
            } else {
                cacheTable.delete(generateDeleteEventChunk(), cacheExpiryCompiledCondition);
            }
            } catch (ConnectionUnavailableException e) {
                throw new SiddhiAppRuntimeException(siddhiAppContext.getName() + ": " + e.getMessage());
            }
        } else {
            readWriteLock.writeLock().lock();
            try {
                AbstractQueryableRecordTable.queryStoreWithoutCheckingCache.set(Boolean.TRUE);
                try {
                    loadedDataFromStore = storeTable.query(stateEventForCaching,
                            storeTable.getCompiledConditionForCaching(),
                            storeTable.getCompiledSelectionForCaching(), storeTable.getOutputAttributesForCaching());
                    storeTable.setStoreTableSize(findEventChunkSize(loadedDataFromStore));
                    storeTable.setStoreSizeLastCheckedTime(siddhiAppContext.getTimestampGenerator().currentTime());
                } finally {
                    AbstractQueryableRecordTable.queryStoreWithoutCheckingCache.set(Boolean.FALSE);
                }
                if (storeTable.getStoreTableSize() <= storeTable.getMaxCacheSize()) {
                    if (storeTable.getCacheLastReloadTime() < siddhiAppContext.getTimestampGenerator().currentTime() +
                            retentionPeriod) {
                        clearCacheAndReload(loadedDataFromStore);
                        storeTable.setCacheLastReloadTime(siddhiAppContext.getTimestampGenerator().currentTime());
                    }
                } else {
                    cacheTable.delete(generateDeleteEventChunk(), cacheExpiryCompiledCondition);
                }
            } catch (Exception e) {
                throw new SiddhiAppRuntimeException(siddhiAppContext.getName() + ": " + e.getMessage());
            } finally {
                readWriteLock.writeLock().unlock();
            }
        }
        log.debug(siddhiAppContext.getName() + ": CacheExpirer ended");
    }

    private void clearCacheAndReload(StreamEvent loadedDataFromStore) {
        readWriteLock.writeLock().lock();
        try {
            ((CacheTable) cacheTable).deleteAll();
            if (loadedDataFromStore != null) {
                ((CacheTable) cacheTable).addStreamEventUptoMaxSize(loadedDataFromStore);
            }
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }


    public Runnable generateCacheExpirer() {
        class CheckAndExpireCache implements Runnable {
            
            public CheckAndExpireCache() {
            }

            @Override
            public void run() {
                try {
                    handleCacheExpiry();
                } catch (Exception e) {
                    throw new SiddhiAppRuntimeException(siddhiAppContext.getName() + ": " + e.getMessage());
                }
            }
        }
        return new CheckAndExpireCache();
    }
}
