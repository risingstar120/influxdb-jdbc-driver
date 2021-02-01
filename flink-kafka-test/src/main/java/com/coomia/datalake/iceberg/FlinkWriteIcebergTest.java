package com.coomia.datalake.iceberg;

import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.flink.TableLoader;
import org.apache.iceberg.flink.sink.FlinkSink;
import org.apache.iceberg.hive.HiveCatalog;
import org.apache.iceberg.types.Types;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.coomia.datalake.kafka.KafkaUtils;

public class FlinkWriteIcebergTest {

  public static void main(String[] args) throws Exception {

    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
    env.getConfig().setAutoWatermarkInterval(5000L);
    env.setParallelism(1);

    // iceberg catalog identification.
    Configuration conf = new Configuration();
    Catalog catalog = new HiveCatalog(conf);

    // iceberg table identification.
    TableIdentifier name = TableIdentifier.of("default", "iceberg-tb");

    // iceberg table schema identification.
    Schema schema = new Schema(Types.NestedField.required(1, "uid", Types.StringType.get()),
        Types.NestedField.optional(2, "eventTime", Types.LongType.get()),
        Types.NestedField.required(3, "eventid", Types.StringType.get()),
        Types.NestedField.optional(4, "uuid", Types.StringType.get()));

    // iceberg table partition identification.
    PartitionSpec spec = PartitionSpec.builderFor(schema).year("ts").bucket("id", 2).build();

    // create an iceberg table.
    Table table = catalog.createTable(name, schema, spec);



    String topic = "arkevent";
    String servers = "kafka:9092";

    FlinkKafkaConsumer<String> consumer = new FlinkKafkaConsumer<String>(topic,
        new SimpleStringSchema(), KafkaUtils.consumeProps(servers, "flink-consumer"));
    consumer.setStartFromLatest();

    SingleOutputStreamOperator<RowData> dataStream =
        env.addSource(consumer).map(new MapFunction<String, RowData>() {

          @Override
          public RowData map(String value) throws Exception {
            JSONObject dataJson = JSON.parseObject(value);
            GenericRowData row = new GenericRowData(2);
            row.setField(0, dataJson.getString("uid"));
            row.setField(1, dataJson.getLong("eventTime"));
            row.setField(2, dataJson.getString("eventid"));
            row.setField(3, dataJson.getString("uuid"));
            return row;
          }


        });
    // uid is used for job restart or something when using savepoint.
    dataStream.uid("flink-consumer");

    TableLoader tableLoader = TableLoader.fromHadoopTable(table.location());

    //sink data to iceberg table
    FlinkSink.forRowData(dataStream).table(table).tableLoader(tableLoader).writeParallelism(1)
        .build();

    // Execute the program.
    env.execute("Test Iceberg DataStream");


  }

}
