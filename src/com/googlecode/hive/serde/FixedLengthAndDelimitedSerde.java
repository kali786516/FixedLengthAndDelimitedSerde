/*
 * Copyright 2016 Sanjiv Singh
 * 
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
 * 
 */
package com.googlecode.hive.serde;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.MissingFormatArgumentException;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.hive.serde2.AbstractSerDe;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.SerDeSpec;
import org.apache.hadoop.hive.serde2.SerDeStats;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.StringObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoFactory;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;

@SerDeSpec(schemaProps = { serdeConstants.LIST_COLUMNS, serdeConstants.LIST_COLUMN_TYPES,
		FixedLengthAndDelimitedSerde.INPUT_FORMAT_STRING, FixedLengthAndDelimitedSerde.INPUT_FORMAT_COLUMN_SEPERATOR })
public class FixedLengthAndDelimitedSerde extends AbstractSerDe {

	public static final Log LOG = LogFactory.getLog(FixedLengthAndDelimitedSerde.class.getName());

	public static final String INPUT_FORMAT_STRING = "input.format.string";
	public static final String INPUT_FORMAT_COLUMN_SEPERATOR = "input.format.column.seperator";

	int numColumns;
	String inputFormatString;
	String inputFormatColumnSeperator;

	StructObjectInspector rowOI;
	ArrayList<String> row;

	@Override
	public void initialize(Configuration conf, Properties tbl) throws SerDeException {

		// We can get the table definition from tbl.

		// Read the configuration parameters
		inputFormatString = tbl.getProperty(INPUT_FORMAT_STRING);
		inputFormatColumnSeperator = tbl.getProperty(INPUT_FORMAT_COLUMN_SEPERATOR);
		if (inputFormatColumnSeperator == null) {
			inputFormatColumnSeperator = "#";
		}

		String columnNameProperty = tbl.getProperty(serdeConstants.LIST_COLUMNS);
		String columnTypeProperty = tbl.getProperty(serdeConstants.LIST_COLUMN_TYPES);

		// Parse the configuration parameters
		List<String> columnNames = Arrays.asList(columnNameProperty.split(","));
		List<TypeInfo> columnTypes = TypeInfoUtils.getTypeInfosFromTypeString(columnTypeProperty);
		assert columnNames.size() == columnTypes.size();
		numColumns = columnNames.size();

		// All columns have to be of type STRING.
		for (int c = 0; c < numColumns; c++) {
			if (!columnTypes.get(c).equals(TypeInfoFactory.stringTypeInfo)) {
				throw new SerDeException(getClass().getName() + " only accepts string columns, but column[" + c
						+ "] named " + columnNames.get(c) + " has type " + columnTypes.get(c));
			}
		}

		// Constructing the row ObjectInspector:
		// The row consists of some string columns, each column will be a java
		// String object.
		List<ObjectInspector> columnOIs = new ArrayList<ObjectInspector>(columnNames.size());
		for (int c = 0; c < numColumns; c++) {
			columnOIs.add(PrimitiveObjectInspectorFactory.javaStringObjectInspector);
		}
		// StandardStruct uses ArrayList to store the row.
		rowOI = ObjectInspectorFactory.getStandardStructObjectInspector(columnNames, columnOIs);

		// Constructing the row object, etc, which will be reused for all rows.
		row = new ArrayList<String>(numColumns);
		for (int c = 0; c < numColumns; c++) {
			row.add(null);
		}
		outputFields = new Object[numColumns];
		outputRowText = new Text();
	}

	@Override
	public ObjectInspector getObjectInspector() throws SerDeException {
		return rowOI;
	}

	@Override
	public Class<? extends Writable> getSerializedClass() {
		return Text.class;
	}

	// Number of rows not matching the regex
	long unmatchedRows = 0;
	long nextUnmatchedRows = 1;
	// Number of rows that match the regex but have missing groups.
	long partialMatchedRows = 0;
	long nextPartialMatchedRows = 1;

	long getNextNumberToDisplay(long now) {
		return now * 10;
	}

	@Override
	public Object deserialize(Writable blob) throws SerDeException {

		if (inputFormatString == null) {
			throw new SerDeException("This table does not have serde property \"input.format.string\"!");
		}
		Text rowText = (Text) blob;
		Map<Integer, String> columnValues = getColumnValues(rowText.toString());

		// If do not match, ignore the line, return a row with all nulls.
		if (columnValues.keySet().size() != numColumns) {
			unmatchedRows++;
			if (unmatchedRows >= nextUnmatchedRows) {
				nextUnmatchedRows = getNextNumberToDisplay(nextUnmatchedRows);
				// Report the row
				LOG.warn("" + unmatchedRows + " unmatched rows are found: " + rowText);
			}
			return null;
		}

		// Otherwise, return the row.
		for (int c = 0; c < numColumns; c++) {
			try {
				row.set(c, columnValues.get(c));
			} catch (RuntimeException e) {
				partialMatchedRows++;
				if (partialMatchedRows >= nextPartialMatchedRows) {
					nextPartialMatchedRows = getNextNumberToDisplay(nextPartialMatchedRows);
					// Report the row
					LOG.warn("" + partialMatchedRows + " partially unmatched rows are found, "
							+ " cannot find column number " + c + ": " + rowText);
				}
				row.set(c, null);
			}
		}
		return row;
	}

	Object[] outputFields;
	Text outputRowText;

	@Override
	public Writable serialize(Object obj, ObjectInspector objInspector) throws SerDeException {

		if (inputFormatString == null) {
			throw new SerDeException("Cannot write data into table because \"input.format.string\""
					+ " is not specified in serde properties of the table.");
		}

		// Get all the fields out.
		// NOTE: The correct way to get fields out of the row is to use
		// objInspector.
		// The obj can be a Java ArrayList, or a Java class, or a byte[] or
		// whatever.
		// The only way to access the data inside the obj is through
		// ObjectInspector.

		StructObjectInspector outputRowOI = (StructObjectInspector) objInspector;
		List<? extends StructField> outputFieldRefs = outputRowOI.getAllStructFieldRefs();
		if (outputFieldRefs.size() != numColumns) {
			throw new SerDeException("Cannot serialize the object because there are " + outputFieldRefs.size()
					+ " fields but the table has " + numColumns + " columns.");
		}

		// Get all data out.
		for (int c = 0; c < numColumns; c++) {
			Object field = outputRowOI.getStructFieldData(obj, outputFieldRefs.get(c));
			ObjectInspector fieldOI = outputFieldRefs.get(c).getFieldObjectInspector();
			// The data must be of type String
			StringObjectInspector fieldStringOI = (StringObjectInspector) fieldOI;
			// Convert the field to Java class String, because objects of String
			// type
			// can be
			// stored in String, Text, or some other classes.
			outputFields[c] = fieldStringOI.getPrimitiveJavaObject(field);
		}

		// Format the String
		String outputRowString = null;
		try {
			outputRowString = getRowString(outputFields);
		} catch (MissingFormatArgumentException e) {
			throw new SerDeException(
					"The table contains " + numColumns + " columns, but the outputFormatString is asking for more.", e);
		}
		outputRowText.set(outputRowString);
		return outputRowText;
	}

	@Override
	public SerDeStats getSerDeStats() {
		// no support for statistics
		return null;
	}

	private Map<Integer, String> getColumnValues(String inputRecordString) {
		String[] columnFormats = inputFormatString.split(inputFormatColumnSeperator);
		if (columnFormats.length != numColumns) {
			throw new MissingFormatArgumentException(
					"Mismatch columnFormats.length : " + columnFormats.length + " between numColumns : " + numColumns);
		}

		int index = 0;
		int cIndex = 0;
		Map<Integer, String> columnValues = new HashMap<Integer, String>();
		try {

			for (String columnFormat : columnFormats) {
				String columnSerdeType = columnFormat.substring(0, 1);
				String columnValue = null;
				if (columnSerdeType.equalsIgnoreCase("FL")) {
					Integer length = Integer.parseInt(columnFormat.substring(2));
					columnValue = inputRecordString.substring(cIndex, cIndex + length);
					cIndex += length;
				} else if (columnSerdeType.equalsIgnoreCase("DM")) {
					String delimit = columnFormat.substring(2);
					int indexOf = inputRecordString.substring(cIndex).indexOf(delimit);
					if (indexOf != -1) {
						columnValue = inputRecordString.substring(cIndex, indexOf + cIndex);
						cIndex = indexOf + cIndex + 1;
					} else {
						return columnValues;
					}
				} else {
					throw new MissingFormatArgumentException(
							"Invalid " + INPUT_FORMAT_STRING + " : " + inputFormatString);
				}
				columnValues.put(index, columnValue);
				index++;
			}

		} catch (Exception e) {
			LOG.warn("error processing row " + inputRecordString);
		}
		return columnValues;
	}

	private String getRowString(Object[] outputFields) {

		String rowString = "";

		String[] columnFormats = inputFormatString.split(inputFormatColumnSeperator);
		int index = 0;
		for (String columnFormat : columnFormats) {
			String columnSerdeType = columnFormat.substring(0, 1);
			String columnValue = null;
			if (columnSerdeType.equalsIgnoreCase("FL")) {
				Integer length = Integer.parseInt(columnFormat.substring(2));
				columnValue = String.format("%1$" + length + "s", outputFields[index]);
			} else if (columnSerdeType.equalsIgnoreCase("DM")) {
				String delimit = columnFormat.substring(2);
				columnValue = outputFields[index] + delimit;
			} else {
				throw new MissingFormatArgumentException("Invalid " + INPUT_FORMAT_STRING + " : " + inputFormatString);
			}
			rowString = rowString + columnValue;
			index++;
		}

		return rowString;
	}

}