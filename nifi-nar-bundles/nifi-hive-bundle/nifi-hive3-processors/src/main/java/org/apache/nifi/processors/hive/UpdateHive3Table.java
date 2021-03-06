/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.hive;

import org.apache.hadoop.hive.ql.io.orc.NiFiOrcUtils;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.RequiresInstanceClassLoading;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.Validator;
import org.apache.nifi.dbcp.hive.Hive3DBCPService;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processor.util.pattern.DiscontinuedException;
import org.apache.nifi.processors.hadoop.exception.RecordReaderFactoryException;
import org.apache.nifi.serialization.RecordReader;
import org.apache.nifi.serialization.RecordReaderFactory;
import org.apache.nifi.serialization.record.RecordField;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;


@Tags({"hive", "metadata", "jdbc", "database", "table"})
@CapabilityDescription("This processor uses a Hive JDBC connection and incoming records to generate any Hive 3.0+ table changes needed to support the incoming records.")
@WritesAttributes({
        @WritesAttribute(attribute = "output.table", description = "This attribute is written on the flow files routed to the 'success' "
                + "and 'failure' relationships, and contains the target table name."),
        @WritesAttribute(attribute = "output.path", description = "This attribute is written on the flow files routed to the 'success' "
                + "and 'failure' relationships, and contains the path on the file system to the table (or partition location if the table is partitioned).")
})
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@RequiresInstanceClassLoading
public class UpdateHive3Table extends AbstractProcessor {

    static final String TEXTFILE = "TEXTFILE";
    static final String SEQUENCEFILE = "SEQUENCEFILE";
    static final String ORC = "ORC";
    static final String PARQUET = "PARQUET";
    static final String AVRO = "AVRO";
    static final String RCFILE = "RCFILE";

    static final AllowableValue TEXTFILE_STORAGE = new AllowableValue(TEXTFILE, TEXTFILE, "Stored as plain text files. TEXTFILE is the default file format, unless the configuration "
            + "parameter hive.default.fileformat has a different setting.");
    static final AllowableValue SEQUENCEFILE_STORAGE = new AllowableValue(SEQUENCEFILE, SEQUENCEFILE, "Stored as compressed Sequence Files.");
    static final AllowableValue ORC_STORAGE = new AllowableValue(ORC, ORC, "Stored as ORC file format. Supports ACID Transactions & Cost-based Optimizer (CBO). "
            + "Stores column-level metadata.");
    static final AllowableValue PARQUET_STORAGE = new AllowableValue(PARQUET, PARQUET, "Stored as Parquet format for the Parquet columnar storage format.");
    static final AllowableValue AVRO_STORAGE = new AllowableValue(AVRO, AVRO, "Stored as Avro format.");
    static final AllowableValue RCFILE_STORAGE = new AllowableValue(RCFILE, RCFILE, "Stored as Record Columnar File format.");

    static final AllowableValue CREATE_IF_NOT_EXISTS = new AllowableValue("Create If Not Exists", "Create If Not Exists",
            "Create a table with the given schema if it does not already exist");
    static final AllowableValue FAIL_IF_NOT_EXISTS = new AllowableValue("Fail If Not Exists", "Fail If Not Exists",
            "If the target does not already exist, log an error and route the flowfile to failure");

    static final String ATTR_OUTPUT_TABLE = "output.table";
    static final String ATTR_OUTPUT_PATH = "output.path";

    // Properties
    static final PropertyDescriptor RECORD_READER = new PropertyDescriptor.Builder()
            .name("record-reader")
            .displayName("Record Reader")
            .description("The service for reading incoming flow files. The reader is only used to determine the schema of the records, the actual records will not be processed.")
            .identifiesControllerService(RecordReaderFactory.class)
            .required(true)
            .build();

    static final PropertyDescriptor HIVE_DBCP_SERVICE = new PropertyDescriptor.Builder()
            .name("hive3-dbcp-service")
            .displayName("Hive Database Connection Pooling Service")
            .description("The Hive Controller Service that is used to obtain connection(s) to the Hive database")
            .required(true)
            .identifiesControllerService(Hive3DBCPService.class)
            .build();

    static final PropertyDescriptor TABLE_NAME = new PropertyDescriptor.Builder()
            .name("hive3-table-name")
            .displayName("Table Name")
            .description("The name of the database table to update. If the table does not exist, then it will either be created or an error thrown, depending "
                    + "on the value of the Create Table property.")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    static final PropertyDescriptor CREATE_TABLE = new PropertyDescriptor.Builder()
            .name("hive3-create-table")
            .displayName("Create Table Strategy")
            .description("Specifies how to process the target table when it does not exist (create it, fail, e.g.).")
            .required(true)
            .addValidator(Validator.VALID)
            .allowableValues(CREATE_IF_NOT_EXISTS, FAIL_IF_NOT_EXISTS)
            .defaultValue(FAIL_IF_NOT_EXISTS.getValue())
            .build();

    static final PropertyDescriptor TABLE_STORAGE_FORMAT = new PropertyDescriptor.Builder()
            .name("hive3-storage-format")
            .displayName("Create Table Storage Format")
            .description("If a table is to be created, the specified storage format will be used.")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .allowableValues(TEXTFILE_STORAGE, SEQUENCEFILE_STORAGE, ORC_STORAGE, PARQUET_STORAGE, AVRO_STORAGE, RCFILE_STORAGE)
            .defaultValue(TEXTFILE)
            .dependsOn(CREATE_TABLE, CREATE_IF_NOT_EXISTS)
            .build();

    static final PropertyDescriptor QUERY_TIMEOUT = new PropertyDescriptor.Builder()
            .name("hive3-query-timeout")
            .displayName("Query timeout")
            .description("Sets the number of seconds the driver will wait for a query to execute. "
                    + "A value of 0 means no timeout. NOTE: Non-zero values may not be supported by the driver.")
            .defaultValue("0")
            .required(true)
            .addValidator(StandardValidators.INTEGER_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    static final PropertyDescriptor STATIC_PARTITION_VALUES = new PropertyDescriptor.Builder()
            .name("hive3-part-vals")
            .displayName("Static Partition Values")
            .description("Specifies a comma-separated list of the values for the partition columns of the target table. This assumes all incoming records belong to the same partition "
                    + "and the partition columns are not fields in the record. If specified, this property will often contain "
                    + "Expression Language. For example if PartitionRecord is upstream and two partition columns 'name' and 'age' are used, then this property can be set to "
                    + "${name},${age}. This property must be set if the table is partitioned, and must not be set if the table is not partitioned. If this property is set, the values "
                    + "will be used as the partition values, and the partition.location value will reflect the location of the partition in the filesystem (for use downstream in "
                    + "processors like PutHDFS).")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    // Relationships
    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("A FlowFile containing records routed to this relationship after the record has been successfully transmitted to Hive.")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("A FlowFile containing records routed to this relationship if the record could not be transmitted to Hive.")
            .build();

    private List<PropertyDescriptor> propertyDescriptors;
    private Set<Relationship> relationships;

    @Override
    protected void init(ProcessorInitializationContext context) {
        List<PropertyDescriptor> props = new ArrayList<>();
        props.add(RECORD_READER);
        props.add(HIVE_DBCP_SERVICE);
        props.add(TABLE_NAME);
        props.add(STATIC_PARTITION_VALUES);
        props.add(CREATE_TABLE);
        props.add(TABLE_STORAGE_FORMAT);
        props.add(QUERY_TIMEOUT);

        propertyDescriptors = Collections.unmodifiableList(props);

        Set<Relationship> _relationships = new HashSet<>();
        _relationships.add(REL_SUCCESS);
        _relationships.add(REL_FAILURE);
        relationships = Collections.unmodifiableSet(_relationships);
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propertyDescriptors;
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {

        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        final RecordReaderFactory recordReaderFactory = context.getProperty(RECORD_READER).asControllerService(RecordReaderFactory.class);
        final String tableName = context.getProperty(TABLE_NAME).evaluateAttributeExpressions(flowFile).getValue();
        final String staticPartitionValuesString = context.getProperty(STATIC_PARTITION_VALUES).evaluateAttributeExpressions(flowFile).getValue();
        List<String> staticPartitionValues = null;
        if (!StringUtils.isEmpty(staticPartitionValuesString)) {
            staticPartitionValues = Arrays.stream(staticPartitionValuesString.split(",")).filter(Objects::nonNull).map(String::trim).collect(Collectors.toList());
        }

        final ComponentLog log = getLogger();

        try {
            final RecordReader reader;

            try (final InputStream in = session.read(flowFile)) {
                // if we fail to create the RecordReader then we want to route to failure, so we need to
                // handle this separately from the other IOExceptions which normally route to retry
                try {
                    reader = recordReaderFactory.createRecordReader(flowFile, in, getLogger());
                } catch (Exception e) {
                    throw new RecordReaderFactoryException("Unable to create RecordReader", e);
                }
            } catch (RecordReaderFactoryException rrfe) {
                log.error(
                        "Failed to create {} for {} - routing to failure",
                        new Object[]{RecordReader.class.getSimpleName(), flowFile},
                        rrfe
                );
                session.transfer(flowFile, REL_FAILURE);
                return;
            }

            RecordSchema recordSchema = reader.getSchema();

            final boolean createIfNotExists = context.getProperty(CREATE_TABLE).getValue().equals(CREATE_IF_NOT_EXISTS.getValue());
            final String storageFormat = context.getProperty(TABLE_STORAGE_FORMAT).getValue();
            final Hive3DBCPService dbcpService = context.getProperty(HIVE_DBCP_SERVICE).asControllerService(Hive3DBCPService.class);
            try (final Connection connection = dbcpService.getConnection()) {

                checkAndUpdateTableSchema(session, flowFile, connection, recordSchema, tableName, staticPartitionValues, createIfNotExists, storageFormat);
                flowFile = session.putAttribute(flowFile, ATTR_OUTPUT_TABLE, tableName);
                session.getProvenanceReporter().invokeRemoteProcess(flowFile, dbcpService.getConnectionURL());
                session.transfer(flowFile, REL_SUCCESS);
            }
        } catch (IOException | SQLException e) {

            flowFile = session.putAttribute(flowFile, ATTR_OUTPUT_TABLE, tableName);
            log.error(
                    "Exception while processing {} - routing to failure",
                    new Object[]{flowFile},
                    e
            );
            session.transfer(flowFile, REL_FAILURE);

        } catch (DiscontinuedException e) {
            // The input FlowFile processing is discontinued. Keep it in the input queue.
            getLogger().warn("Discontinued processing for {} due to {}", new Object[]{flowFile, e}, e);
            session.transfer(flowFile, Relationship.SELF);
        } catch (Throwable t) {
            throw (t instanceof ProcessException) ? (ProcessException) t : new ProcessException(t);
        }
    }

    private synchronized void checkAndUpdateTableSchema(final ProcessSession session, final FlowFile flowFile, final Connection conn, final RecordSchema schema,
                                                        final String tableName, final List<String> partitionValues,
                                                        final boolean createIfNotExists, final String storageFormat) throws IOException {
        // Read in the current table metadata, compare it to the reader's schema, and
        // add any columns from the schema that are missing in the table
        try (Statement s = conn.createStatement()) {
            // Determine whether the table exists
            ResultSet tables = s.executeQuery("SHOW TABLES");
            List<String> tableNames = new ArrayList<>();
            String hiveTableName;
            while (tables.next() && StringUtils.isNotEmpty(hiveTableName = tables.getString(1))) {
                tableNames.add(hiveTableName);
            }

            List<String> columnsToAdd = new ArrayList<>();
            String outputPath;
            if (!tableNames.contains(tableName) && createIfNotExists) {
                StringBuilder createTableStatement = new StringBuilder();
                for (RecordField recordField : schema.getFields()) {
                    String recordFieldName = recordField.getFieldName();
                    // The field does not exist in the table, add it
                    columnsToAdd.add(recordFieldName + " " + NiFiOrcUtils.getHiveTypeFromFieldType(recordField.getDataType(), true));
                    getLogger().debug("Adding column " + recordFieldName + " to table " + tableName);
                }
                createTableStatement.append("CREATE TABLE IF NOT EXISTS ")
                        .append(tableName)
                        .append(" (")
                        .append(String.join(", ", columnsToAdd))
                        .append(") STORED AS ")
                        .append(storageFormat);

                String createTableSql = createTableStatement.toString();

                if (StringUtils.isNotEmpty(createTableSql)) {
                    // Perform the table create
                    getLogger().info("Executing Hive DDL: " + createTableSql);
                    s.execute(createTableSql);
                }

                // Now that the table is created, describe it and determine its location (for placing the flowfile downstream)
                String describeTable = "DESC FORMATTED " + tableName;
                ResultSet tableInfo = s.executeQuery(describeTable);
                boolean moreRows = tableInfo.next();
                boolean locationFound = false;
                while (moreRows && !locationFound) {
                    String line = tableInfo.getString(1);
                    if (line.startsWith("Location:")) {
                        locationFound = true;
                        continue; // Don't do a next() here, need to get the second column value
                    }
                    moreRows = tableInfo.next();
                }
                outputPath = tableInfo.getString(2);

            } else {
                List<String> hiveColumns = new ArrayList<>();

                String describeTable = "DESC FORMATTED " + tableName;
                ResultSet tableInfo = s.executeQuery(describeTable);
                // Result is 3 columns, col_name, data_type, comment. Check the first row for a header and skip if so, otherwise add column name
                tableInfo.next();
                String columnName = tableInfo.getString(1);
                if (StringUtils.isNotEmpty(columnName) && !columnName.startsWith("#")) {
                    hiveColumns.add(columnName);
                }
                // If the column was a header, check for a blank line to follow and skip it, otherwise add the column name
                if (columnName.startsWith("#")) {
                    tableInfo.next();
                    columnName = tableInfo.getString(1);
                    if (StringUtils.isNotEmpty(columnName)) {
                        hiveColumns.add(columnName);
                    }
                }

                // Collect all column names
                while (tableInfo.next() && StringUtils.isNotEmpty(columnName = tableInfo.getString(1))) {
                    hiveColumns.add(columnName);
                }

                // Collect all partition columns
                boolean moreRows = true;
                boolean headerFound = false;
                while (moreRows && !headerFound) {
                    String line = tableInfo.getString(1);
                    if ("# Partition Information".equals(line)) {
                        headerFound = true;
                    } else if ("# Detailed Table Information".equals(line)) {
                        // Not partitioned, exit the loop with headerFound = false
                        break;
                    }
                    moreRows = tableInfo.next();
                }

                List<String> partitionColumns = new ArrayList<>();
                List<String> partitionColumnsEqualsValueList = new ArrayList<>();
                List<String> partitionColumnsLocationList = new ArrayList<>();
                if (headerFound) {
                    // If the table is partitioned, construct the partition=value strings for each partition column
                    String partitionColumnName;
                    columnName = tableInfo.getString(1);
                    if (StringUtils.isNotEmpty(columnName) && !columnName.startsWith("#")) {
                        hiveColumns.add(columnName);
                    }
                    // If the column was a header, check for a blank line to follow and skip it, otherwise add the column name
                    if (columnName.startsWith("#")) {
                        tableInfo.next();
                        columnName = tableInfo.getString(1);
                        if (StringUtils.isNotEmpty(columnName)) {
                            partitionColumns.add(columnName);
                        }
                    }
                    while (tableInfo.next() && StringUtils.isNotEmpty(partitionColumnName = tableInfo.getString(1))) {
                        partitionColumns.add(partitionColumnName);
                    }

                    final int partitionColumnsSize = partitionColumns.size();
                    if (partitionValues == null) {
                        throw new IOException("Found " + partitionColumnsSize + " partition columns but no Static Partition Values were supplied");
                    }
                    final int partitionValuesSize = partitionValues.size();
                    if (partitionValuesSize < partitionColumnsSize) {
                        throw new IOException("Found " + partitionColumnsSize + " partition columns but only " + partitionValuesSize + " Static Partition Values were supplied");
                    }

                    for (int i = 0; i < partitionColumns.size(); i++) {
                        partitionColumnsEqualsValueList.add(partitionColumns.get(i) + "='" + partitionValues.get(i) + "'");
                        // Add unquoted version for the output path
                        partitionColumnsLocationList.add(partitionColumns.get(i) + "=" + partitionValues.get(i));
                    }
                }

                // Get table location
                moreRows = true;
                headerFound = false;
                while (moreRows && !headerFound) {
                    String line = tableInfo.getString(1);
                    if (line.startsWith("Location:")) {
                        headerFound = true;
                        continue; // Don't do a next() here, need to get the second column value
                    }
                    moreRows = tableInfo.next();
                }
                String tableLocation = tableInfo.getString(2);


                StringBuilder alterTableStatement = new StringBuilder();
                // Handle new columns
                for (RecordField recordField : schema.getFields()) {
                    String recordFieldName = recordField.getFieldName().toLowerCase();
                    if (!hiveColumns.contains(recordFieldName) && !partitionColumns.contains(recordFieldName)) {
                        // The field does not exist in the table (and is not a partition column), add it
                        columnsToAdd.add(recordFieldName + " " + NiFiOrcUtils.getHiveTypeFromFieldType(recordField.getDataType(), true));
                        getLogger().info("Adding column " + recordFieldName + " to table " + tableName);
                    }
                }

                String alterTableSql;
                if (!columnsToAdd.isEmpty()) {
                    alterTableStatement.append("ALTER TABLE ")
                            .append(tableName)
                            .append(" ADD COLUMNS (")
                            .append(String.join(", ", columnsToAdd))
                            .append(")");

                    alterTableSql = alterTableStatement.toString();
                    if (StringUtils.isNotEmpty(alterTableSql)) {
                        // Perform the table update
                        getLogger().info("Executing Hive DDL: " + alterTableSql);
                        s.execute(alterTableSql);
                    }
                }

                outputPath = tableLocation;

                // Handle new partitions
                if (!partitionColumnsEqualsValueList.isEmpty()) {
                    alterTableSql = "ALTER TABLE " +
                            tableName +
                            " ADD IF NOT EXISTS PARTITION (" +
                            String.join(", ", partitionColumnsEqualsValueList) +
                            ")";
                    if (StringUtils.isNotEmpty(alterTableSql)) {
                        // Perform the table update
                        getLogger().info("Executing Hive DDL: " + alterTableSql);
                        s.execute(alterTableSql);
                    }
                    // Add attribute for HDFS location of the partition values
                    outputPath = tableLocation + "/" + String.join("/", partitionColumnsLocationList);
                }
            }

            session.putAttribute(flowFile, ATTR_OUTPUT_PATH, outputPath);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
