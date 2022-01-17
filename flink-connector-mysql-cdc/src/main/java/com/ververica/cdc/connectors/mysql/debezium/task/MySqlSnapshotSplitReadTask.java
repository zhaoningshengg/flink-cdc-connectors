/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.cdc.connectors.mysql.debezium.task;

import com.ververica.cdc.connectors.mysql.debezium.dispatcher.EventDispatcherImpl;
import com.ververica.cdc.connectors.mysql.debezium.dispatcher.SignalEventDispatcher;
import com.ververica.cdc.connectors.mysql.debezium.reader.SnapshotSplitReader;
import com.ververica.cdc.connectors.mysql.source.offset.BinlogOffset;
import com.ververica.cdc.connectors.mysql.source.split.MySqlSnapshotSplit;
import com.ververica.cdc.connectors.mysql.source.utils.StatementUtils;
import io.debezium.DebeziumException;
import io.debezium.connector.mysql.MySqlConnection;
import io.debezium.connector.mysql.MySqlConnectorConfig;
import io.debezium.connector.mysql.MySqlDatabaseSchema;
import io.debezium.connector.mysql.MySqlOffsetContext;
import io.debezium.connector.mysql.MySqlValueConverters;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.AbstractSnapshotChangeEventSource;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.pipeline.spi.ChangeRecordEmitter;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.pipeline.spi.SnapshotResult;
import io.debezium.relational.Column;
import io.debezium.relational.RelationalSnapshotChangeEventSource;
import io.debezium.relational.SnapshotChangeRecordEmitter;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.schema.SchemaChangeEvent;
import io.debezium.schema.TopicSelector;
import io.debezium.util.Clock;
import io.debezium.util.ColumnUtils;
import io.debezium.util.Strings;
import io.debezium.util.Threads;
import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.sql.Blob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.ververica.cdc.connectors.mysql.debezium.DebeziumUtils.currentBinlogOffset;

/**
 * Task to read snapshot split of table.
 */
public class MySqlSnapshotSplitReadTask extends AbstractSnapshotChangeEventSource {

    private static final Logger LOG = LoggerFactory.getLogger(MySqlSnapshotSplitReadTask.class);

    /**
     * Interval for showing a log statement with the progress while scanning a single table.
     */
    private static final Duration LOG_INTERVAL = Duration.ofMillis(10_000);

    private final MySqlConnectorConfig connectorConfig;
    private final MySqlDatabaseSchema databaseSchema;
    private final MySqlConnection jdbcConnection;
    private final EventDispatcherImpl<TableId> dispatcher;
    private final Clock clock;
    private final MySqlSnapshotSplit snapshotSplit;
    private final MySqlOffsetContext offsetContext;
    private final TopicSelector<TableId> topicSelector;
    private final SnapshotProgressListener snapshotProgressListener;
    private final List<SchemaChangeEvent> schemaEvents = new ArrayList<>();

    public MySqlSnapshotSplitReadTask(
            MySqlConnectorConfig connectorConfig,
            MySqlOffsetContext previousOffset,
            SnapshotProgressListener snapshotProgressListener,
            MySqlDatabaseSchema databaseSchema,
            MySqlConnection jdbcConnection,
            EventDispatcherImpl<TableId> dispatcher,
            TopicSelector<TableId> topicSelector,
            Clock clock,
            MySqlSnapshotSplit snapshotSplit) {
        super(connectorConfig, previousOffset, snapshotProgressListener);
        this.offsetContext = previousOffset;
        this.connectorConfig = connectorConfig;
        this.databaseSchema = databaseSchema;
        this.jdbcConnection = jdbcConnection;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.snapshotSplit = snapshotSplit;
        this.topicSelector = topicSelector;
        this.snapshotProgressListener = snapshotProgressListener;
    }

    @Override
    public SnapshotResult execute(ChangeEventSourceContext context) throws InterruptedException {
        SnapshottingTask snapshottingTask = getSnapshottingTask(previousOffset);
        final SnapshotContext ctx;
        try {
            ctx = prepare(context);
        } catch (Exception e) {
            LOG.error("Failed to initialize snapshot context.", e);
            throw new RuntimeException(e);
        }
        try {
            return doExecute(context, ctx, snapshottingTask);
        } catch (InterruptedException e) {
            LOG.warn("Snapshot was interrupted before completion");
            throw e;
        } catch (Exception t) {
            throw new DebeziumException(t);
        }
    }

    @Override
    protected SnapshotResult doExecute(
            ChangeEventSourceContext context,
            SnapshotContext snapshotContext,
            SnapshottingTask snapshottingTask)
            throws Exception {
        final RelationalSnapshotChangeEventSource.RelationalSnapshotContext ctx =
                (RelationalSnapshotChangeEventSource.RelationalSnapshotContext) snapshotContext;
        ctx.offset = offsetContext;

        final SignalEventDispatcher signalEventDispatcher =
                new SignalEventDispatcher(
                        offsetContext.getPartition(),
                        topicSelector.topicNameFor(snapshotSplit.getTableId()),
                        dispatcher.getQueue());

        final BinlogOffset lowWatermark = currentBinlogOffset(jdbcConnection);
        LOG.info(
                "Snapshot step 1 - Determining low watermark {} for split {}",
                lowWatermark,
                snapshotSplit);
        ((SnapshotSplitReader.SnapshotSplitChangeEventSourceContextImpl) (context))
                .setLowWatermark(lowWatermark);
        signalEventDispatcher.dispatchWatermarkEvent(
                snapshotSplit, lowWatermark, SignalEventDispatcher.WatermarkKind.LOW);

        if (snapshotSplit.isSnapshotSplit() && snapshotSplit.getSplitStart() == null && connectorConfig.includeSchemaChangeRecords()) {
            LOG.info(
                    "Snapshot step 1.1 - initial schemas for table {}",
                    snapshotSplit.getTableId().table());
            determineCapturedTables(ctx);
            createSchemaEventsForTables(ctx, ctx.capturedSchemaTables, true);
            createSchemaChangeEventsForTables(context, ctx, snapshottingTask);

        }

        LOG.info("Snapshot step 2 - Snapshotting data");
        createDataEvents(ctx, snapshotSplit.getTableId());

        final BinlogOffset highWatermark = currentBinlogOffset(jdbcConnection);
        LOG.info(
                "Snapshot step 3 - Determining high watermark {} for split {}",
                highWatermark,
                snapshotSplit);
        signalEventDispatcher.dispatchWatermarkEvent(
                snapshotSplit, highWatermark, SignalEventDispatcher.WatermarkKind.HIGH);
        ((SnapshotSplitReader.SnapshotSplitChangeEventSourceContextImpl) (context))
                .setHighWatermark(highWatermark);

        return SnapshotResult.completed(ctx.offset);
    }

    @Override
    protected SnapshottingTask getSnapshottingTask(OffsetContext previousOffset) {
        return new SnapshottingTask(false, true);
    }

    @Override
    protected SnapshotContext prepare(ChangeEventSourceContext changeEventSourceContext)
            throws Exception {
        return new MySqlSnapshotContext();
    }

    private static class MySqlSnapshotContext
            extends RelationalSnapshotChangeEventSource.RelationalSnapshotContext {

        public MySqlSnapshotContext() throws SQLException {
            super("");
        }
    }

    private void createDataEvents(
            RelationalSnapshotChangeEventSource.RelationalSnapshotContext snapshotContext,
            TableId tableId)
            throws Exception {
        EventDispatcher.SnapshotReceiver snapshotReceiver =
                dispatcher.getSnapshotChangeEventReceiver();
        LOG.debug("Snapshotting table {}", tableId);
        createDataEventsForTable(
                snapshotContext, snapshotReceiver, databaseSchema.tableFor(tableId));
        snapshotReceiver.completeSnapshot();
    }

    /**
     * Dispatches the data change events for the records of a single table.
     */
    private void createDataEventsForTable(
            RelationalSnapshotChangeEventSource.RelationalSnapshotContext snapshotContext,
            EventDispatcher.SnapshotReceiver snapshotReceiver,
            Table table)
            throws InterruptedException {

        long exportStart = clock.currentTimeInMillis();
        LOG.info("Exporting data from split '{}' of table {}", snapshotSplit.splitId(), table.id());

        final String selectSql =
                StatementUtils.buildSplitScanQuery(
                        snapshotSplit.getTableId(),
                        snapshotSplit.getSplitKeyType(),
                        snapshotSplit.getSplitStart() == null,
                        snapshotSplit.getSplitEnd() == null);
        LOG.info(
                "For split '{}' of table {} using select statement: '{}'",
                snapshotSplit.splitId(),
                table.id(),
                selectSql);

        try (PreparedStatement selectStatement =
                     StatementUtils.readTableSplitDataStatement(
                             jdbcConnection,
                             selectSql,
                             snapshotSplit.getSplitStart() == null,
                             snapshotSplit.getSplitEnd() == null,
                             snapshotSplit.getSplitStart(),
                             snapshotSplit.getSplitEnd(),
                             snapshotSplit.getSplitKeyType().getFieldCount(),
                             connectorConfig.getQueryFetchSize());
             ResultSet rs = selectStatement.executeQuery()) {

            ColumnUtils.ColumnArray columnArray = ColumnUtils.toArray(rs, table);
            long rows = 0;
            Threads.Timer logTimer = getTableScanLogTimer();

            while (rs.next()) {
                rows++;
                final Object[] row = new Object[columnArray.getGreatestColumnPosition()];
                for (int i = 0; i < columnArray.getColumns().length; i++) {
                    Column actualColumn = table.columns().get(i);
                    row[columnArray.getColumns()[i].position() - 1] =
                            readField(rs, i + 1, actualColumn, table);
                }
                if (logTimer.expired()) {
                    long stop = clock.currentTimeInMillis();
                    LOG.info(
                            "Exported {} records for split '{}' after {}",
                            rows,
                            snapshotSplit.splitId(),
                            Strings.duration(stop - exportStart));
                    snapshotProgressListener.rowsScanned(table.id(), rows);
                    logTimer = getTableScanLogTimer();
                }
                dispatcher.dispatchSnapshotEvent(
                        table.id(),
                        getChangeRecordEmitter(snapshotContext, table.id(), row),
                        snapshotReceiver);
            }
            LOG.info(
                    "Finished exporting {} records for split '{}', total duration '{}'",
                    rows,
                    snapshotSplit.splitId(),
                    Strings.duration(clock.currentTimeInMillis() - exportStart));
        } catch (SQLException e) {
            throw new ConnectException("Snapshotting of table " + table.id() + " failed", e);
        }
    }

    protected ChangeRecordEmitter getChangeRecordEmitter(
            SnapshotContext snapshotContext, TableId tableId, Object[] row) {
        snapshotContext.offset.event(tableId, clock.currentTime());
        return new SnapshotChangeRecordEmitter(snapshotContext.offset, row, clock);
    }

    private Threads.Timer getTableScanLogTimer() {
        return Threads.timer(clock, LOG_INTERVAL);
    }

    /**
     * Read JDBC return value and deal special type like time, timestamp.
     *
     * <p>Note https://issues.redhat.com/browse/DBZ-3238 has fixed this issue, please remove this
     * method once we bump Debezium version to 1.6
     */
    private Object readField(ResultSet rs, int fieldNo, Column actualColumn, Table actualTable)
            throws SQLException {
        if (actualColumn.jdbcType() == Types.TIME) {
            return readTimeField(rs, fieldNo);
        } else if (actualColumn.jdbcType() == Types.DATE) {
            return readDateField(rs, fieldNo, actualColumn, actualTable);
        }
        // This is for DATETIME columns (a logical date + time without time zone)
        // by reading them with a calendar based on the default time zone, we make sure that the
        // value
        // is constructed correctly using the database's (or connection's) time zone
        else if (actualColumn.jdbcType() == Types.TIMESTAMP) {
            return readTimestampField(rs, fieldNo, actualColumn, actualTable);
        }
        // JDBC's rs.GetObject() will return a Boolean for all TINYINT(1) columns.
        // TINYINT columns are reprtoed as SMALLINT by JDBC driver
        else if (actualColumn.jdbcType() == Types.TINYINT
                || actualColumn.jdbcType() == Types.SMALLINT) {
            // It seems that rs.wasNull() returns false when default value is set and NULL is
            // inserted
            // We thus need to use getObject() to identify if the value was provided and if yes then
            // read it again to get correct scale
            return rs.getObject(fieldNo) == null ? null : rs.getInt(fieldNo);
        }
        // DBZ-2673
        // It is necessary to check the type names as types like ENUM and SET are
        // also reported as JDBC type char
        else if ("CHAR".equals(actualColumn.typeName())
                || "VARCHAR".equals(actualColumn.typeName())
                || "TEXT".equals(actualColumn.typeName())) {
            return rs.getBytes(fieldNo);
        } else {
            return rs.getObject(fieldNo);
        }
    }

    /**
     * As MySQL connector/J implementation is broken for MySQL type "TIME" we have to use a
     * binary-ish workaround. https://issues.jboss.org/browse/DBZ-342
     */
    private Object readTimeField(ResultSet rs, int fieldNo) throws SQLException {
        Blob b = rs.getBlob(fieldNo);
        if (b == null) {
            return null; // Don't continue parsing time field if it is null
        }

        try {
            return MySqlValueConverters.stringToDuration(
                    new String(b.getBytes(1, (int) (b.length())), "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            LOG.error("Could not read MySQL TIME value as UTF-8");
            throw new RuntimeException(e);
        }
    }

    /**
     * In non-string mode the date field can contain zero in any of the date part which we need to
     * handle as all-zero.
     */
    private Object readDateField(ResultSet rs, int fieldNo, Column column, Table table)
            throws SQLException {
        Blob b = rs.getBlob(fieldNo);
        if (b == null) {
            return null; // Don't continue parsing date field if it is null
        }

        try {
            return MySqlValueConverters.stringToLocalDate(
                    new String(b.getBytes(1, (int) (b.length())), "UTF-8"), column, table);
        } catch (UnsupportedEncodingException e) {
            LOG.error("Could not read MySQL TIME value as UTF-8");
            throw new RuntimeException(e);
        }
    }

    /**
     * In non-string mode the time field can contain zero in any of the date part which we need to
     * handle as all-zero.
     */
    private Object readTimestampField(ResultSet rs, int fieldNo, Column column, Table table)
            throws SQLException {
        Blob b = rs.getBlob(fieldNo);
        if (b == null) {
            return null; // Don't continue parsing timestamp field if it is null
        }

        try {
            return MySqlValueConverters.containsZeroValuesInDatePart(
                    (new String(b.getBytes(1, (int) (b.length())), "UTF-8")), column, table)
                    ? null
                    : rs.getTimestamp(fieldNo, Calendar.getInstance());
        } catch (UnsupportedEncodingException e) {
            LOG.error("Could not read MySQL TIME value as UTF-8");
            throw new RuntimeException(e);
        }
    }

    private void determineCapturedTables(RelationalSnapshotChangeEventSource.RelationalSnapshotContext ctx) throws Exception {
//        Set<TableId> allTableIds = determineDataCollectionsToBeSnapshotted(getAllTableIds(ctx)).collect(Collectors.toSet());

        Set<TableId> capturedTables = new HashSet<>();
        Set<TableId> capturedSchemaTables = new HashSet<>();
        capturedTables.add(this.snapshotSplit.getTableId());
        capturedSchemaTables.add(this.snapshotSplit.getTableId());
//        for (TableId tableId : allTableIds) {
//            if (connectorConfig.getTableFilters().eligibleDataCollectionFilter().isIncluded(tableId)) {
//                LOG.trace("Adding table {} to the list of capture schema tables", tableId);
//                capturedSchemaTables.add(tableId);
//            }
//            if (connectorConfig.getTableFilters().dataCollectionFilter().isIncluded(tableId)) {
//                LOG.trace("Adding table {} to the list of captured tables", tableId);
//                capturedTables.add(tableId);
//            } else {
//                LOG.trace("Ignoring table {} as it's not included in the filter configuration", tableId);
//            }
//        }
        ctx.capturedTables = sort(capturedTables);
        ctx.capturedSchemaTables = capturedSchemaTables
                .stream()
                .sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    protected Set<TableId> getAllTableIds(RelationalSnapshotChangeEventSource.RelationalSnapshotContext ctx) throws Exception {
        // -------------------
        // READ DATABASE NAMES
        // -------------------
        // Get the list of databases ...
        LOG.info("Read list of available databases");
        final List<String> databaseNames = new ArrayList<>();
        this.jdbcConnection.query("SHOW DATABASES", rs -> {
            while (rs.next()) {
                databaseNames.add(rs.getString(1));
            }
        });
        LOG.info("\t list of available databases is: {}", databaseNames);

        // ----------------
        // READ TABLE NAMES
        // ----------------
        // Get the list of table IDs for each database. We can't use a prepared statement with MySQL, so we have to
        // build the SQL statement each time. Although in other cases this might lead to SQL injection, in our case
        // we are reading the database names from the database and not taking them from the user ...
        LOG.info("Read list of available tables in each database");
        final Set<TableId> tableIds = new HashSet<>();
        final Set<String> readableDatabaseNames = new HashSet<>();
        for (String dbName : databaseNames) {
            try {
                // MySQL sometimes considers some local files as databases (see DBZ-164),
                // so we will simply try each one and ignore the problematic ones ...
                this.jdbcConnection.query("SHOW FULL TABLES IN " + quote(dbName) + " where Table_Type = 'BASE TABLE'", rs -> {
                    while (rs.next()) {
                        TableId id = new TableId(dbName, null, rs.getString(1));
                        tableIds.add(id);
                    }
                });
                readableDatabaseNames.add(dbName);
            } catch (SQLException e) {
                // We were unable to execute the query or process the results, so skip this ...
                LOG.warn("\t skipping database '{}' due to error reading tables: {}", dbName, e.getMessage());
            }
        }
        final Set<String> includedDatabaseNames = readableDatabaseNames.stream().filter(this.connectorConfig.getTableFilters().databaseFilter()).collect(Collectors.toSet());
        LOG.info("\tsnapshot continuing with database(s): {}", includedDatabaseNames);
        return tableIds;
    }

    private String quote(String dbOrTableName) {
        return "`" + dbOrTableName + "`";
    }

    private String quote(TableId id) {
        return quote(id.catalog()) + "." + quote(id.table());
    }

    private Set<TableId> sort(Set<TableId> capturedTables) throws Exception {
        String tableIncludeList = connectorConfig.tableIncludeList();
        if (tableIncludeList != null) {
            return Strings.listOfRegex(tableIncludeList, Pattern.CASE_INSENSITIVE)
                    .stream()
                    .flatMap(pattern -> toTableIds(capturedTables, pattern))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return capturedTables
                .stream()
                .sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Stream<TableId> toTableIds(Set<TableId> tableIds, Pattern pattern) {
        return tableIds
                .stream()
                .filter(tid -> pattern.asPredicate().test(tid.toString()))
                .sorted();
    }

    void createSchemaEventsForTables(RelationalSnapshotChangeEventSource.RelationalSnapshotContext snapshotContext, final Collection<TableId> tablesToRead, final boolean firstPhase) throws SQLException {
        for (TableId tableId : tablesToRead) {
            jdbcConnection.query("SHOW CREATE TABLE " + quote(tableId), rs -> {
                if (rs.next()) {
                    addSchemaEvent(snapshotContext, tableId.catalog(), rs.getString(2));
                }
            });
        }
    }

    private void addSchemaEvent(RelationalSnapshotChangeEventSource.RelationalSnapshotContext snapshotContext, String database, String ddl) {
        schemaEvents.addAll(databaseSchema.parseSnapshotDdl(ddl, database, (MySqlOffsetContext) snapshotContext.offset,
                clock.currentTimeAsInstant()));
    }

    protected void createSchemaChangeEventsForTables(ChangeEventSourceContext sourceContext,
                                                     RelationalSnapshotChangeEventSource.RelationalSnapshotContext snapshotContext, SnapshottingTask snapshottingTask)
            throws Exception {
        tryStartingSnapshot(snapshotContext);

        for (Iterator<SchemaChangeEvent> i = schemaEvents.iterator(); i.hasNext(); ) {
            final SchemaChangeEvent event = i.next();
//            if (!sourceContext.isRunning()) {
//                throw new InterruptedException("Interrupted while processing event " + event);
//            }

            if (databaseSchema.skipSchemaChangeEvent(event)) {
                continue;
            }

            LOG.debug("Processing schema event {}", event);
//            final TableId tableId = event.getTables().isEmpty() ? null : event.getTables().iterator().next().id();
            snapshotContext.offset.event(this.snapshotSplit.getTableId(), clock.currentTime());

//            // If data are not snapshotted then the last schema change must set last snapshot flag
//            if (!snapshottingTask.snapshotData() && !i.hasNext()) {
//                lastSnapshotRecord(snapshotContext);
//            }
            dispatcher.dispatchSchemaChangeEvent(this.snapshotSplit.getTableId(), (receiver) -> receiver.schemaChangeEvent(event));
        }

        // Make schema available for snapshot source
        databaseSchema.tableIds().forEach(x -> snapshotContext.tables.overwriteTable(databaseSchema.tableFor(x)));
    }

    protected void tryStartingSnapshot(RelationalSnapshotChangeEventSource.RelationalSnapshotContext snapshotContext) {
        if (!snapshotContext.offset.isSnapshotRunning()) {
            snapshotContext.offset.preSnapshotStart();
        }
    }

//    protected void lastSnapshotRecord(RelationalSnapshotChangeEventSource.RelationalSnapshotContext snapshotContext) {
//        if (delayedSchemaSnapshotTables.isEmpty()) {
//            snapshotContext.offset.markLastSnapshotRecord();
//        }
//    }
}
