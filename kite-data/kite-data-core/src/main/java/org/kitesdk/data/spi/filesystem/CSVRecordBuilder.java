/*
 * Copyright 2013 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kitesdk.data.spi.filesystem;

import java.util.List;
import javax.annotation.Nullable;
import org.apache.avro.AvroRuntimeException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.IndexedRecord;
import org.apache.avro.reflect.ReflectData;
import org.kitesdk.data.DatasetReaderException;
import org.kitesdk.data.DatasetRecordException;
import org.kitesdk.data.spi.SchemaUtil;

class CSVRecordBuilder<E> {
  private final Schema schema;
  private final Class<E> recordClass;
  private final Schema.Field[] fields;
  private final int[] indexes; // Record position to CSV field position

  public CSVRecordBuilder(Schema schema, Class<E> recordClass,
                          @Nullable List<String> header) {
    this.schema = schema;
    this.recordClass = recordClass;

    // initialize the index and field arrays
    fields = schema.getFields().toArray(new Schema.Field[schema.getFields().size()]);
    indexes = new int[fields.length];

    if (header != null) {
      for (int i = 0; i < fields.length; i += 1) {
        fields[i] = schema.getFields().get(i);
        indexes[i] = Integer.MAX_VALUE; // never present in the row
      }

      // there's a header in next
      for (int i = 0; i < header.size(); i += 1) {
        Schema.Field field = schema.getField(header.get(i));
        if (field != null) {
          indexes[field.pos()] = i;
        }
      }

    } else {
      // without a header, map to fields by position
      for (int i = 0; i < fields.length; i += 1) {
        fields[i] = schema.getFields().get(i);
        indexes[i] = i;
      }
    }
  }

  public E makeRecord(String[] fields, @Nullable E reuse) {
    try {
      E record = reuse;
      if (record == null) {
        record = newRecordInstance();
      }

      if (record instanceof IndexedRecord) {
        fillIndexed((IndexedRecord) record, fields);
      } else {
        fillReflect(record, fields);
      }

      return record;
    } catch (AvroRuntimeException e) {
      // expected when a field has no default and no value can be constructed
      throw new DatasetRecordException("Unable to construct record", e);
    } catch (NumberFormatException e) {
      // expected when a value should be a number but can't be parsed
      throw new DatasetRecordException("Unable to construct record", e);
    }
  }

  @SuppressWarnings("unchecked")
  private E newRecordInstance() {
    if (recordClass != GenericData.Record.class && !recordClass.isInterface()) {
      E record = (E) ReflectData.newInstance(recordClass, schema);
      if (record != null) {
        return record;
      }
    }
    return (E) new GenericData.Record(schema);
  }

  private void fillIndexed(IndexedRecord record, String[] data) {
    for (int i = 0; i < indexes.length; i += 1) {
      int index = indexes[i];
      record.put(i,
          makeValue(index < data.length ? data[index] : null, fields[i]));
    }
  }

  private void fillReflect(Object record, String[] data) {
    for (int i = 0; i < indexes.length; i += 1) {
      Schema.Field field = fields[i];
      int index = indexes[i];
      Object value = makeValue(index < data.length ? data[index] : null, field);
      ReflectData.get().setField(record, field.name(), i, value);
    }
  }

  private static Object makeValue(@Nullable String string, Schema.Field field) {
    Object value = makeValue(string, field.schema());
    if (value != null || SchemaUtil.nullOk(field.schema())) {
      return value;
    } else {
      // this will fail if there is no default value
      return ReflectData.get().getDefaultValue(field);
    }
  }

  /**
   * Returns a the value as the first matching schema type or null.
   *
   * Note that if the value may be null even if the schema does not allow the
   * value to be null.
   *
   * @param string a String representation of the value
   * @param schema a Schema
   * @return the string coerced to the correct type from the schema or null
   */
  private static Object makeValue(@Nullable String string, Schema schema) {
    if (string == null) {
      return null;
    }

    try {
      switch (schema.getType()) {
        case BOOLEAN:
          return Boolean.valueOf(string);
        case STRING:
          return string;
        case FLOAT:
          return Float.valueOf(string);
        case DOUBLE:
          return Double.valueOf(string);
        case INT:
          return Integer.valueOf(string);
        case LONG:
          return Long.valueOf(string);
        case ENUM:
          // TODO: translate to enum class
          if (schema.hasEnumSymbol(string)) {
            return string;
          } else {
            try {
              return schema.getEnumSymbols().get(Integer.parseInt(string));
            } catch (IndexOutOfBoundsException ex) {
              return null;
            }
          }
        case UNION:
          Object value = null;
          for (Schema possible : schema.getTypes()) {
            value = makeValue(string, possible);
            if (value != null) {
              return value;
            }
          }
          return null;
        case NULL:
          return null;
        default:
          // FIXED, BYTES, MAP, ARRAY, RECORD are not supported
          throw new DatasetReaderException(
              "Unsupported field type:" + schema.getType());
      }
    } catch (NumberFormatException e) {
      // empty string is considered null for numeric types
      if (string.isEmpty()) {
        return null;
      } else {
        throw e;
      }
    }
  }
}
