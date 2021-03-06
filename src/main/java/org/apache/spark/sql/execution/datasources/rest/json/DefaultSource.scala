package org.apache.spark.sql.execution.datasources.rest.json

import java.io.{ByteArrayOutputStream, CharArrayWriter}
import java.net.URL
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

import com.fasterxml.jackson.core._
import com.google.common.base.Objects
import net.sf.json.{JSONArray, JSONObject}
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat
import org.apache.hadoop.mapreduce.{Job, RecordWriter, TaskAttemptContext}
import org.apache.http.client.fluent.Request
import org.apache.http.util.EntityUtils
import org.apache.spark.Logging
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.mapred.SparkHadoopMapRedUtil
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.HiveTypeCoercion
import org.apache.spark.sql.catalyst.expressions.GenericMutableRow
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.execution.datasources.rest.json.JacksonUtils.nextUntil
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{AnalysisException, Row, SQLContext}
import org.apache.spark.unsafe.types.UTF8String
import streaming.common.JSONPath

import scala.collection.mutable.ArrayBuffer

/**
 * 7/22/16 WilliamZhu(allwefantasy@gmail.com)
 */
class DefaultSource extends RelationProvider with DataSourceRegister {
  override def shortName(): String = "restJSON"

  override def createRelation(
                               sqlContext: SQLContext,
                               parameters: Map[String, String]
                               ): BaseRelation = {
    val samplingRatio = parameters.get("samplingRatio").map(_.toDouble).getOrElse(1.0)
    val url = parameters.getOrElse("url", "")
    val xPath = parameters.getOrElse("xPath", "$")
    val tryTimes = parameters.get("tryTimes").map(_.toInt).getOrElse(3)

    new RestJSONRelation(None, url, xPath, samplingRatio, tryTimes, None)(sqlContext)
  }

}

private[sql] class RestJSONRelation(
                                     val inputRDD: Option[RDD[String]],
                                     val url: String,
                                     val xPath: String,
                                     val samplingRatio: Double,
                                     val tryFetchTimes: Int,
                                     val maybeDataSchema: Option[StructType]
                                     )(@transient val sqlContext: SQLContext)
  extends BaseRelation with TableScan {

  /** Constraints to be imposed on schema to be stored. */
  private def checkConstraints(schema: StructType): Unit = {
    if (schema.fieldNames.length != schema.fieldNames.distinct.length) {
      val duplicateColumns = schema.fieldNames.groupBy(identity).collect {
        case (x, ys) if ys.length > 1 => "\"" + x + "\""
      }.mkString(", ")
      throw new AnalysisException(s"Duplicate column(s) : $duplicateColumns found, " +
        s"cannot save to JSON format")
    }
  }

  override val needConversion: Boolean = false

  override def schema: StructType = dataSchema


  private def createBaseRdd(inputPaths: Array[String]): RDD[String] = {
    val counter = new AtomicInteger()
    val success = new AtomicBoolean(false)
    val holder = new AtomicReference[RDD[String]]()
    do {
      tryCreateBaseRDD(inputPaths: Array[String]) match {
        case Some(i) =>
          counter.incrementAndGet()
          success.set(true)
          holder.set(i)
        case None =>
          counter.incrementAndGet()
          throw new RuntimeException(s"try fetch ${inputPaths(0)} ${tryFetchTimes} times,still fail.")
      }
    } while (!success.get() && counter.get() < tryFetchTimes)
    holder.get()
  }

  private def tryCreateBaseRDD(inputPaths: Array[String]): Option[RDD[String]] = {
    val url = inputPaths.head
    val res = Request.Get(new URL(url).toURI).execute()
    val response = res.returnResponse()
    val content = EntityUtils.toString(response.getEntity)
    if (response != null && response.getStatusLine.getStatusCode == 200) {
      import scala.collection.JavaConversions._
      val extractContent = JSONArray.fromObject(JSONPath.read(content, xPath)).
        map(f => JSONObject.fromObject(f).toString).toSeq
      Some(sqlContext.sparkContext.makeRDD(extractContent))
    } else {
      None
    }
  }


  lazy val dataSchema = {
    val jsonSchema = maybeDataSchema.getOrElse {
      InferSchema(
        inputRDD.getOrElse(createBaseRdd(Array(url))),
        samplingRatio,
        sqlContext.conf.columnNameOfCorruptRecord)
    }
    checkConstraints(jsonSchema)

    jsonSchema
  }

  def buildScan(): RDD[Row] = {
    JacksonParser(
      inputRDD.getOrElse(createBaseRdd(Array(url))),
      dataSchema,
      sqlContext.conf.columnNameOfCorruptRecord).asInstanceOf[RDD[Row]]
  }

  override def equals(other: Any): Boolean = other match {
    case that: RestJSONRelation =>
      ((inputRDD, that.inputRDD) match {
        case (Some(thizRdd), Some(thatRdd)) => thizRdd eq thatRdd
        case (None, None) => true
        case _ => false
      }) && url == that.url &&
        dataSchema == that.dataSchema &&
        schema == that.schema
    case _ => false
  }

  override def hashCode(): Int = {
    Objects.hashCode(
      inputRDD,
      url,
      dataSchema,
      schema)
  }

  def prepareJobForWrite(job: Job): OutputWriterFactory = {
    new OutputWriterFactory {
      override def newInstance(
                                path: String,
                                dataSchema: StructType,
                                context: TaskAttemptContext): OutputWriter = {
        new JsonOutputWriter(path, dataSchema, context)
      }
    }
  }
}

private class JsonOutputWriter(
                                path: String,
                                dataSchema: StructType,
                                context: TaskAttemptContext)
  extends OutputWriter with SparkHadoopMapRedUtil with Logging {

  val writer = new CharArrayWriter()
  // create the Generator without separator inserted between 2 records
  val gen = new JsonFactory().createGenerator(writer).setRootValueSeparator(null)

  val result = new Text()

  private val recordWriter: RecordWriter[NullWritable, Text] = {
    new TextOutputFormat[NullWritable, Text]() {
      override def getDefaultWorkFile(context: TaskAttemptContext, extension: String): Path = {
        val configuration = SparkHadoopUtil.get.getConfigurationFromJobContext(context)
        val uniqueWriteJobId = configuration.get("spark.sql.sources.writeJobUUID")
        val taskAttemptId = SparkHadoopUtil.get.getTaskAttemptIDFromTaskAttemptContext(context)
        val split = taskAttemptId.getTaskID.getId
        new Path(path, f"part-r-$split%05d-$uniqueWriteJobId$extension")
      }
    }.getRecordWriter(context)
  }

  override def write(row: Row): Unit = throw new UnsupportedOperationException("call writeInternal")

  override protected[sql] def writeInternal(row: InternalRow): Unit = {
    JacksonGenerator(dataSchema, gen, row)
    gen.flush()

    result.set(writer.toString)
    writer.reset()

    recordWriter.write(NullWritable.get(), result)
  }

  override def close(): Unit = {
    gen.close()
    recordWriter.close(context)
  }
}

private[sql] object InferSchema {
  /**
   * Infer the type of a collection of json records in three stages:
   * 1. Infer the type of each record
   * 2. Merge types by choosing the lowest type necessary to cover equal keys
   * 3. Replace any remaining null fields with string, the top type
   */
  def apply(
             json: RDD[String],
             samplingRatio: Double = 1.0,
             columnNameOfCorruptRecords: String): StructType = {
    require(samplingRatio > 0, s"samplingRatio ($samplingRatio) should be greater than 0")
    val schemaData = if (samplingRatio > 0.99) {
      json
    } else {
      json.sample(withReplacement = false, samplingRatio, 1)
    }

    // perform schema inference on each row and merge afterwards
    val rootType = schemaData.mapPartitions { iter =>
      val factory = new JsonFactory()
      iter.map { row =>
        try {
          val parser = factory.createParser(row)
          parser.nextToken()
          inferField(parser)
        } catch {
          case _: JsonParseException =>
            StructType(Seq(StructField(columnNameOfCorruptRecords, StringType)))
        }
      }
    }.treeAggregate[DataType](StructType(Seq()))(compatibleRootType, compatibleRootType)

    canonicalizeType(rootType) match {
      case Some(st: StructType) => st
      case _ =>
        // canonicalizeType erases all empty structs, including the only one we want to keep
        StructType(Seq())
    }
  }

  /**
   * Infer the type of a json document from the parser's token stream
   */
  private def inferField(parser: JsonParser): DataType = {
    import com.fasterxml.jackson.core.JsonToken._
    parser.getCurrentToken match {
      case null | VALUE_NULL => NullType

      case FIELD_NAME =>
        parser.nextToken()
        inferField(parser)

      case VALUE_STRING if parser.getTextLength < 1 =>
        // Zero length strings and nulls have special handling to deal
        // with JSON generators that do not distinguish between the two.
        // To accurately infer types for empty strings that are really
        // meant to represent nulls we assume that the two are isomorphic
        // but will defer treating null fields as strings until all the
        // record fields' types have been combined.
        NullType

      case VALUE_STRING => StringType
      case START_OBJECT =>
        val builder = Seq.newBuilder[StructField]
        while (nextUntil(parser, END_OBJECT)) {
          builder += StructField(parser.getCurrentName, inferField(parser), nullable = true)
        }

        StructType(builder.result().sortBy(_.name))

      case START_ARRAY =>
        // If this JSON array is empty, we use NullType as a placeholder.
        // If this array is not empty in other JSON objects, we can resolve
        // the type as we pass through all JSON objects.
        var elementType: DataType = NullType
        while (nextUntil(parser, END_ARRAY)) {
          elementType = compatibleType(elementType, inferField(parser))
        }

        ArrayType(elementType)

      case VALUE_NUMBER_INT | VALUE_NUMBER_FLOAT =>
        import JsonParser.NumberType._
        parser.getNumberType match {
          // For Integer values, use LongType by default.
          case INT | LONG => LongType
          // Since we do not have a data type backed by BigInteger,
          // when we see a Java BigInteger, we use DecimalType.
          case BIG_INTEGER | BIG_DECIMAL =>
            val v = parser.getDecimalValue
            DecimalType(v.precision(), v.scale())
          case FLOAT | DOUBLE =>
            // TODO(davies): Should we use decimal if possible?
            DoubleType
        }

      case VALUE_TRUE | VALUE_FALSE => BooleanType
    }
  }

  /**
   * Convert NullType to StringType and remove StructTypes with no fields
   */
  private def canonicalizeType: DataType => Option[DataType] = {
    case at@ArrayType(elementType, _) =>
      for {
        canonicalType <- canonicalizeType(elementType)
      } yield {
        at.copy(canonicalType)
      }

    case StructType(fields) =>
      val canonicalFields = for {
        field <- fields
        if field.name.nonEmpty
        canonicalType <- canonicalizeType(field.dataType)
      } yield {
          field.copy(dataType = canonicalType)
        }

      if (canonicalFields.nonEmpty) {
        Some(StructType(canonicalFields))
      } else {
        // per SPARK-8093: empty structs should be deleted
        None
      }

    case NullType => Some(StringType)
    case other => Some(other)
  }

  /**
   * Remove top-level ArrayType wrappers and merge the remaining schemas
   */
  private def compatibleRootType: (DataType, DataType) => DataType = {
    case (ArrayType(ty1, _), ty2) => compatibleRootType(ty1, ty2)
    case (ty1, ArrayType(ty2, _)) => compatibleRootType(ty1, ty2)
    case (ty1, ty2) => compatibleType(ty1, ty2)
  }

  /**
   * Returns the most general data type for two given data types.
   */
  private[json] def compatibleType(t1: DataType, t2: DataType): DataType = {
    HiveTypeCoercion.findTightestCommonTypeOfTwo(t1, t2).getOrElse {
      // t1 or t2 is a StructType, ArrayType, or an unexpected type.
      (t1, t2) match {
        // Double support larger range than fixed decimal, DecimalType.Maximum should be enough
        // in most case, also have better precision.
        case (DoubleType, t: DecimalType) =>
          DoubleType
        case (t: DecimalType, DoubleType) =>
          DoubleType
        case (t1: DecimalType, t2: DecimalType) =>
          val scale = math.max(t1.scale, t2.scale)
          val range = math.max(t1.precision - t1.scale, t2.precision - t2.scale)
          if (range + scale > 38) {
            // DecimalType can't support precision > 38
            DoubleType
          } else {
            DecimalType(range + scale, scale)
          }

        case (StructType(fields1), StructType(fields2)) =>
          val newFields = (fields1 ++ fields2).groupBy(field => field.name).map {
            case (name, fieldTypes) =>
              val dataType = fieldTypes.view.map(_.dataType).reduce(compatibleType)
              StructField(name, dataType, nullable = true)
          }
          StructType(newFields.toSeq.sortBy(_.name))

        case (ArrayType(elementType1, containsNull1), ArrayType(elementType2, containsNull2)) =>
          ArrayType(compatibleType(elementType1, elementType2), containsNull1 || containsNull2)

        // strings and every string is a Json object.
        case (_, _) => StringType
      }
    }
  }
}

import com.fasterxml.jackson.core.{JsonParser, JsonToken}

private object JacksonUtils {
  /**
   * Advance the parser until a null or a specific token is found
   */
  def nextUntil(parser: JsonParser, stopOn: JsonToken): Boolean = {
    parser.nextToken() match {
      case null => false
      case x => x != stopOn
    }
  }
}

private[sql] object JacksonParser {
  def apply(
             json: RDD[String],
             schema: StructType,
             columnNameOfCorruptRecords: String): RDD[InternalRow] = {
    parseJson(json, schema, columnNameOfCorruptRecords)
  }

  /**
   * Parse the current token (and related children) according to a desired schema
   */
  private[sql] def convertField(
                                 factory: JsonFactory,
                                 parser: JsonParser,
                                 schema: DataType): Any = {
    import com.fasterxml.jackson.core.JsonToken._
    (parser.getCurrentToken, schema) match {
      case (null | VALUE_NULL, _) =>
        null

      case (FIELD_NAME, _) =>
        parser.nextToken()
        convertField(factory, parser, schema)

      case (VALUE_STRING, StringType) =>
        UTF8String.fromString(parser.getText)

      case (VALUE_STRING, _) if parser.getTextLength < 1 =>
        // guard the non string type
        null

      case (VALUE_STRING, BinaryType) =>
        parser.getBinaryValue

      case (VALUE_STRING, DateType) =>
        val stringValue = parser.getText
        if (stringValue.contains("-")) {
          // The format of this string will probably be "yyyy-mm-dd".
          DateTimeUtils.millisToDays(DateTimeUtils.stringToTime(parser.getText).getTime)
        } else {
          // In Spark 1.5.0, we store the data as number of days since epoch in string.
          // So, we just convert it to Int.
          stringValue.toInt
        }

      case (VALUE_STRING, TimestampType) =>
        // This one will lose microseconds parts.
        // See https://issues.apache.org/jira/browse/SPARK-10681.
        DateTimeUtils.stringToTime(parser.getText).getTime * 1000L

      case (VALUE_NUMBER_INT, TimestampType) =>
        parser.getLongValue * 1000L

      case (_, StringType) =>
        val writer = new ByteArrayOutputStream()
        val generator = factory.createGenerator(writer, JsonEncoding.UTF8)
        generator.copyCurrentStructure(parser)
        generator.close()
        UTF8String.fromBytes(writer.toByteArray)

      case (VALUE_NUMBER_INT | VALUE_NUMBER_FLOAT, FloatType) =>
        parser.getFloatValue

      case (VALUE_STRING, FloatType) =>
        // Special case handling for NaN and Infinity.
        val value = parser.getText
        val lowerCaseValue = value.toLowerCase()
        if (lowerCaseValue.equals("nan") ||
          lowerCaseValue.equals("infinity") ||
          lowerCaseValue.equals("-infinity") ||
          lowerCaseValue.equals("inf") ||
          lowerCaseValue.equals("-inf")) {
          value.toFloat
        } else {
          sys.error(s"Cannot parse $value as FloatType.")
        }

      case (VALUE_NUMBER_INT | VALUE_NUMBER_FLOAT, DoubleType) =>
        parser.getDoubleValue

      case (VALUE_STRING, DoubleType) =>
        // Special case handling for NaN and Infinity.
        val value = parser.getText
        val lowerCaseValue = value.toLowerCase()
        if (lowerCaseValue.equals("nan") ||
          lowerCaseValue.equals("infinity") ||
          lowerCaseValue.equals("-infinity") ||
          lowerCaseValue.equals("inf") ||
          lowerCaseValue.equals("-inf")) {
          value.toDouble
        } else {
          sys.error(s"Cannot parse $value as DoubleType.")
        }

      case (VALUE_NUMBER_INT | VALUE_NUMBER_FLOAT, dt: DecimalType) =>
        Decimal(parser.getDecimalValue, dt.precision, dt.scale)

      case (VALUE_NUMBER_INT, ByteType) =>
        parser.getByteValue

      case (VALUE_NUMBER_INT, ShortType) =>
        parser.getShortValue

      case (VALUE_NUMBER_INT, IntegerType) =>
        parser.getIntValue

      case (VALUE_NUMBER_INT, LongType) =>
        parser.getLongValue

      case (VALUE_TRUE, BooleanType) =>
        true

      case (VALUE_FALSE, BooleanType) =>
        false

      case (START_OBJECT, st: StructType) =>
        convertObject(factory, parser, st)

      case (START_ARRAY, st: StructType) =>
        // SPARK-3308: support reading top level JSON arrays and take every element
        // in such an array as a row
        convertArray(factory, parser, st)

      case (START_ARRAY, ArrayType(st, _)) =>
        convertArray(factory, parser, st)

      case (START_OBJECT, ArrayType(st, _)) =>
        // the business end of SPARK-3308:
        // when an object is found but an array is requested just wrap it in a list
        convertField(factory, parser, st) :: Nil

      case (START_OBJECT, MapType(StringType, kt, _)) =>
        convertMap(factory, parser, kt)

      case (_, udt: UserDefinedType[_]) =>
        convertField(factory, parser, udt.sqlType)

      case (token, dataType) =>
        sys.error(s"Failed to parse a value for data type $dataType (current token: $token).")
    }
  }

  /**
   * Parse an object from the token stream into a new Row representing the schema.
   *
   * Fields in the json that are not defined in the requested schema will be dropped.
   */
  private def convertObject(
                             factory: JsonFactory,
                             parser: JsonParser,
                             schema: StructType): InternalRow = {
    val row = new GenericMutableRow(schema.length)
    while (nextUntil(parser, JsonToken.END_OBJECT)) {
      schema.getFieldIndex(parser.getCurrentName) match {
        case Some(index) =>
          row.update(index, convertField(factory, parser, schema(index).dataType))

        case None =>
          parser.skipChildren()
      }
    }

    row
  }

  /**
   * Parse an object as a Map, preserving all fields
   */
  private def convertMap(
                          factory: JsonFactory,
                          parser: JsonParser,
                          valueType: DataType): MapData = {
    val keys = ArrayBuffer.empty[UTF8String]
    val values = ArrayBuffer.empty[Any]
    while (nextUntil(parser, JsonToken.END_OBJECT)) {
      keys += UTF8String.fromString(parser.getCurrentName)
      values += convertField(factory, parser, valueType)
    }
    ArrayBasedMapData(keys.toArray, values.toArray)
  }

  private def convertArray(
                            factory: JsonFactory,
                            parser: JsonParser,
                            elementType: DataType): ArrayData = {
    val values = ArrayBuffer.empty[Any]
    while (nextUntil(parser, JsonToken.END_ARRAY)) {
      values += convertField(factory, parser, elementType)
    }

    new GenericArrayData(values.toArray)
  }

  private def parseJson(
                         json: RDD[String],
                         schema: StructType,
                         columnNameOfCorruptRecords: String): RDD[InternalRow] = {

    def failedRecord(record: String): Seq[InternalRow] = {
      // create a row even if no corrupt record column is present
      val row = new GenericMutableRow(schema.length)
      for (corruptIndex <- schema.getFieldIndex(columnNameOfCorruptRecords)) {
        require(schema(corruptIndex).dataType == StringType)
        row.update(corruptIndex, UTF8String.fromString(record))
      }

      Seq(row)
    }

    json.mapPartitions { iter =>
      val factory = new JsonFactory()

      iter.flatMap { record =>
        try {
          val parser = factory.createParser(record)
          parser.nextToken()

          convertField(factory, parser, schema) match {
            case null => failedRecord(record)
            case row: InternalRow => row :: Nil
            case array: ArrayData =>
              if (array.numElements() == 0) {
                Nil
              } else {
                array.toArray[InternalRow](schema)
              }
            case _ =>
              sys.error(
                s"Failed to parse record $record. Please make sure that each line of the file " +
                  "(or each string in the RDD) is a valid JSON object or an array of JSON objects.")
          }
        } catch {
          case _: JsonProcessingException =>
            failedRecord(record)
        }
      }
    }
  }
}

private[sql] object JacksonGenerator {
  /** Transforms a single Row to JSON using Jackson
    *
    * @param rowSchema the schema object used for conversion
    * @param gen a JsonGenerator object
    * @param row The row to convert
    */
  def apply(rowSchema: StructType, gen: JsonGenerator)(row: Row): Unit = {
    def valWriter: (DataType, Any) => Unit = {
      case (_, null) | (NullType, _) => gen.writeNull()
      case (StringType, v: String) => gen.writeString(v)
      case (TimestampType, v: java.sql.Timestamp) => gen.writeString(v.toString)
      case (IntegerType, v: Int) => gen.writeNumber(v)
      case (ShortType, v: Short) => gen.writeNumber(v)
      case (FloatType, v: Float) => gen.writeNumber(v)
      case (DoubleType, v: Double) => gen.writeNumber(v)
      case (LongType, v: Long) => gen.writeNumber(v)
      case (DecimalType(), v: java.math.BigDecimal) => gen.writeNumber(v)
      case (ByteType, v: Byte) => gen.writeNumber(v.toInt)
      case (BinaryType, v: Array[Byte]) => gen.writeBinary(v)
      case (BooleanType, v: Boolean) => gen.writeBoolean(v)
      case (DateType, v) => gen.writeString(v.toString)
      case (udt: UserDefinedType[_], v) => valWriter(udt.sqlType, udt.serialize(v))

      case (ArrayType(ty, _), v: Seq[_]) =>
        gen.writeStartArray()
        v.foreach(valWriter(ty, _))
        gen.writeEndArray()

      case (MapType(kv, vv, _), v: Map[_, _]) =>
        gen.writeStartObject()
        v.foreach { p =>
          gen.writeFieldName(p._1.toString)
          valWriter(vv, p._2)
        }
        gen.writeEndObject()

      case (StructType(ty), v: Row) =>
        gen.writeStartObject()
        ty.zip(v.toSeq).foreach {
          case (_, null) =>
          case (field, v) =>
            gen.writeFieldName(field.name)
            valWriter(field.dataType, v)
        }
        gen.writeEndObject()

      // For UDT, udt.serialize will produce SQL types. So, we need the following three cases.
      case (ArrayType(ty, _), v: ArrayData) =>
        gen.writeStartArray()
        v.foreach(ty, (_, value) => valWriter(ty, value))
        gen.writeEndArray()

      case (MapType(kt, vt, _), v: MapData) =>
        gen.writeStartObject()
        v.foreach(kt, vt, { (k, v) =>
          gen.writeFieldName(k.toString)
          valWriter(vt, v)
        })
        gen.writeEndObject()

      case (StructType(ty), v: InternalRow) =>
        gen.writeStartObject()
        var i = 0
        while (i < ty.length) {
          val field = ty(i)
          val value = v.get(i, field.dataType)
          if (value != null) {
            gen.writeFieldName(field.name)
            valWriter(field.dataType, value)
          }
          i += 1
        }
        gen.writeEndObject()

      case (dt, v) =>
        sys.error(
          s"Failed to convert value $v (class of ${v.getClass}}) with the type of $dt to JSON.")
    }

    valWriter(rowSchema, row)
  }

  /** Transforms a single InternalRow to JSON using Jackson
    *
    * TODO: make the code shared with the other apply method.
    *
    * @param rowSchema the schema object used for conversion
    * @param gen a JsonGenerator object
    * @param row The row to convert
    */
  def apply(rowSchema: StructType, gen: JsonGenerator, row: InternalRow): Unit = {
    def valWriter: (DataType, Any) => Unit = {
      case (_, null) | (NullType, _) => gen.writeNull()
      case (StringType, v) => gen.writeString(v.toString)
      case (TimestampType, v: Long) => gen.writeString(DateTimeUtils.toJavaTimestamp(v).toString)
      case (IntegerType, v: Int) => gen.writeNumber(v)
      case (ShortType, v: Short) => gen.writeNumber(v)
      case (FloatType, v: Float) => gen.writeNumber(v)
      case (DoubleType, v: Double) => gen.writeNumber(v)
      case (LongType, v: Long) => gen.writeNumber(v)
      case (DecimalType(), v: Decimal) => gen.writeNumber(v.toJavaBigDecimal)
      case (ByteType, v: Byte) => gen.writeNumber(v.toInt)
      case (BinaryType, v: Array[Byte]) => gen.writeBinary(v)
      case (BooleanType, v: Boolean) => gen.writeBoolean(v)
      case (DateType, v: Int) => gen.writeString(DateTimeUtils.toJavaDate(v).toString)
      // For UDT values, they should be in the SQL type's corresponding value type.
      // We should not see values in the user-defined class at here.
      // For example, VectorUDT's SQL type is an array of double. So, we should expect that v is
      // an ArrayData at here, instead of a Vector.
      case (udt: UserDefinedType[_], v) => valWriter(udt.sqlType, v)

      case (ArrayType(ty, _), v: ArrayData) =>
        gen.writeStartArray()
        v.foreach(ty, (_, value) => valWriter(ty, value))
        gen.writeEndArray()

      case (MapType(kt, vt, _), v: MapData) =>
        gen.writeStartObject()
        v.foreach(kt, vt, { (k, v) =>
          gen.writeFieldName(k.toString)
          valWriter(vt, v)
        })
        gen.writeEndObject()

      case (StructType(ty), v: InternalRow) =>
        gen.writeStartObject()
        var i = 0
        while (i < ty.length) {
          val field = ty(i)
          val value = v.get(i, field.dataType)
          if (value != null) {
            gen.writeFieldName(field.name)
            valWriter(field.dataType, value)
          }
          i += 1
        }
        gen.writeEndObject()

      case (dt, v) =>
        sys.error(
          s"Failed to convert value $v (class of ${v.getClass}}) with the type of $dt to JSON.")
    }

    valWriter(rowSchema, row)
  }
}


