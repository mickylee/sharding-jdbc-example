/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingjdbc.example.orchestration;

import com.google.common.collect.Lists;
import io.shardingjdbc.core.api.HintManager;
import io.shardingjdbc.core.api.config.MasterSlaveRuleConfiguration;
import io.shardingjdbc.core.api.config.ShardingRuleConfiguration;
import io.shardingjdbc.core.api.config.TableRuleConfiguration;
import io.shardingjdbc.core.api.config.strategy.InlineShardingStrategyConfiguration;
import io.shardingjdbc.core.api.config.strategy.StandardShardingStrategyConfiguration;
import io.shardingjdbc.example.orchestration.algorithm.ModuloTableShardingAlgorithm;
import io.shardingjdbc.orchestration.api.OrchestrationShardingDataSourceFactory;
import io.shardingjdbc.orchestration.api.config.OrchestrationConfiguration;
import io.shardingjdbc.orchestration.reg.zookeeper.ZookeeperConfiguration;
import org.apache.commons.dbcp.BasicDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public final class OrchestrationShardingMasterSlaveMain {
    
    private static final String ZOOKEEPER_CONNECTION_STRING = "localhost:2181";
    
    private static final String NAMESPACE = "orchestration-demo";
    
    // CHECKSTYLE:OFF
    public static void main(final String[] args) throws IOException, SQLException {
    // CHECKSTYLE:ON
        DataSource dataSource = OrchestrationShardingDataSourceFactory.createDataSource(
                createDataSourceMap(), createShardingRuleConfig(), new ConcurrentHashMap<String, Object>(), new Properties(), 
                new OrchestrationConfiguration("orchestration-sharding-master-slave-data-source", getZookeeperConfiguration(), false));
        createTable(dataSource);
        insertData(dataSource);
        printSimpleSelect(dataSource);
        System.out.println("--------------");
        printGroupBy(dataSource);
        System.out.println("--------------");
        printHintSimpleSelect(dataSource);
        dropTable(dataSource);
    }
    
    private static ZookeeperConfiguration getZookeeperConfiguration() {
        ZookeeperConfiguration result = new ZookeeperConfiguration();
        result.setServerLists(ZOOKEEPER_CONNECTION_STRING);
        result.setNamespace(NAMESPACE);
        return result;
    }
    
    private static Map<String, DataSource> createDataSourceMap() {
        Map<String, DataSource> result = new HashMap<>();
        result.put("demo_ds_master_0", createDataSource("demo_ds_master_0"));
        result.put("demo_ds_master_0_slave_0", createDataSource("demo_ds_master_0_slave_0"));
        result.put("demo_ds_master_0_slave_1", createDataSource("demo_ds_master_0_slave_1"));
        result.put("demo_ds_master_1", createDataSource("demo_ds_master_1"));
        result.put("demo_ds_master_1_slave_0", createDataSource("demo_ds_master_1_slave_0"));
        result.put("demo_ds_master_1_slave_1", createDataSource("demo_ds_master_1_slave_1"));
        return result;
    }
    
    private static DataSource createDataSource(final String dataSourceName) {
        BasicDataSource result = new BasicDataSource();
        result.setDriverClassName(com.mysql.jdbc.Driver.class.getName());
        result.setUrl(String.format("jdbc:mysql://localhost:3306/%s", dataSourceName));
        result.setUsername("root");
        result.setPassword("");
        return result;
    }
    
    private static ShardingRuleConfiguration createShardingRuleConfig() throws SQLException {
        ShardingRuleConfiguration result = new ShardingRuleConfiguration();
        TableRuleConfiguration orderTableRuleConfig = new TableRuleConfiguration();
        orderTableRuleConfig.setLogicTable("t_order");
        orderTableRuleConfig.setActualDataNodes("demo_ds_${0..1}.t_order_${0..1}");
        result.getTableRuleConfigs().add(orderTableRuleConfig);
        TableRuleConfiguration orderItemTableRuleConfig = new TableRuleConfiguration();
        orderItemTableRuleConfig.setLogicTable("t_order_item");
        orderItemTableRuleConfig.setActualDataNodes("demo_ds_${0..1}.t_order_item_${0..1}");
        result.getTableRuleConfigs().add(orderItemTableRuleConfig);
        result.getBindingTableGroups().add("t_order, t_order_item");
        result.setDefaultDatabaseShardingStrategyConfig(new InlineShardingStrategyConfiguration("user_id", "demo_ds_${user_id % 2}"));
        result.setDefaultTableShardingStrategyConfig(new StandardShardingStrategyConfiguration("order_id", ModuloTableShardingAlgorithm.class.getName()));
        result.setMasterSlaveRuleConfigs(crateMasterSlaveRuleConfigs());
        return result;
    }
    
    private static List<MasterSlaveRuleConfiguration> crateMasterSlaveRuleConfigs() throws SQLException {
        final MasterSlaveRuleConfiguration config1 = new MasterSlaveRuleConfiguration();
        config1.setName("demo_ds_0");
        config1.setMasterDataSourceName("demo_ds_master_0");
        config1.setSlaveDataSourceNames(Lists.newArrayList("demo_ds_master_0_slave_0", "demo_ds_master_0_slave_1"));
        List<MasterSlaveRuleConfiguration> result = new ArrayList<>();
        result.add(config1);
        final MasterSlaveRuleConfiguration config2 = new MasterSlaveRuleConfiguration();
        config2.setName("demo_ds_1");
        config2.setMasterDataSourceName("demo_ds_master_1");
        config2.setSlaveDataSourceNames(Lists.newArrayList("demo_ds_master_1_slave_0", "demo_ds_master_1_slave_1"));
        result.add(config2);
        return result;
    }
    
    private static void createTable(final DataSource dataSource) throws SQLException {
        executeUpdate(dataSource, "CREATE TABLE IF NOT EXISTS t_order (order_id BIGINT NOT NULL AUTO_INCREMENT, user_id INT NOT NULL, status VARCHAR(50), PRIMARY KEY (order_id))");
        executeUpdate(dataSource, "CREATE TABLE IF NOT EXISTS t_order_item (item_id BIGINT NOT NULL AUTO_INCREMENT, order_id BIGINT NOT NULL, user_id INT NOT NULL, PRIMARY KEY (item_id))");
    }
    
    private static void insertData(final DataSource dataSource) throws SQLException {
        for (int orderId = 1000; orderId < 1010; orderId++) {
            executeUpdate(dataSource, String.format("INSERT INTO t_order (order_id, user_id, status) VALUES (%s, 10, 'INIT')", orderId));
            executeUpdate(dataSource, String.format("INSERT INTO t_order_item (item_id, order_id, user_id) VALUES (%s01, %s, 10)", orderId, orderId));
        }
        for (int orderId = 1100; orderId < 1110; orderId++) {
            executeUpdate(dataSource, String.format("INSERT INTO t_order (order_id, user_id, status) VALUES (%s, 11, 'INIT')", orderId));
            executeUpdate(dataSource, String.format("INSERT INTO t_order_item (item_id, order_id, user_id) VALUES (%s01, %s, 11)", orderId, orderId));
        }
    }
    
    private static void printSimpleSelect(final DataSource dataSource) throws SQLException {
        String sql = "SELECT i.* FROM t_order o JOIN t_order_item i ON o.order_id=i.order_id WHERE o.user_id=? AND o.order_id=?";
        try (
                Connection conn = dataSource.getConnection();
                PreparedStatement preparedStatement = conn.prepareStatement(sql)) {
            preparedStatement.setInt(1, 10);
            preparedStatement.setInt(2, 1001);
            try (ResultSet rs = preparedStatement.executeQuery()) {
                while (rs.next()) {
                    System.out.println(rs.getInt(1));
                    System.out.println(rs.getInt(2));
                    System.out.println(rs.getInt(3));
                }
            }
        }
    }
    
    private static void printGroupBy(final DataSource dataSource) throws SQLException {
        String sql = "SELECT o.user_id, COUNT(*) FROM t_order o JOIN t_order_item i ON o.order_id=i.order_id GROUP BY o.user_id";
        try (
                Connection conn = dataSource.getConnection();
                PreparedStatement preparedStatement = conn.prepareStatement(sql)
        ) {
            ResultSet rs = preparedStatement.executeQuery();
            while (rs.next()) {
                System.out.println("user_id: " + rs.getInt(1) + ", count: " + rs.getInt(2));
            }
        }
    }
    
    private static void printHintSimpleSelect(final DataSource dataSource) throws SQLException {
        String sql = "SELECT i.* FROM t_order o JOIN t_order_item i ON o.order_id=i.order_id";
        try (
                HintManager hintManager = HintManager.getInstance();
                Connection conn = dataSource.getConnection();
                PreparedStatement preparedStatement = conn.prepareStatement(sql)) {
            hintManager.addDatabaseShardingValue("t_order", "user_id", 10);
            hintManager.addTableShardingValue("t_order", "order_id", 1001);
            try (ResultSet rs = preparedStatement.executeQuery()) {
                while (rs.next()) {
                    System.out.println(rs.getInt(1));
                    System.out.println(rs.getInt(2));
                    System.out.println(rs.getInt(3));
                }
            }
        }
    }
    
    private static void dropTable(final DataSource dataSource) throws SQLException {
        executeUpdate(dataSource, "DROP TABLE t_order_item");
        executeUpdate(dataSource, "DROP TABLE t_order");
    }
    
    private static void executeUpdate(final DataSource dataSource, final String sql) throws SQLException {
        try (
                Connection conn = dataSource.getConnection();
                PreparedStatement preparedStatement = conn.prepareStatement(sql)) {
            preparedStatement.executeUpdate();
        }
    }
}
