package com.engineeringplatform.console;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL Schema Import (V06-WORK-005).
 *
 * Reads information_schema.tables / columns / statistics via JDBC and maps a
 * table into Business Module Contract fields. Password is used ONLY for the
 * JDBC connection — never written to contract, logs, or generated files.
 */
public final class MySqlImportService {

    public record ConnectionInfo(String host, int port, String database, String username, String password) {
        String jdbcUrl() {
            return "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        }
    }

    public boolean testConnection(ConnectionInfo info) {
        try (Connection c = DriverManager.getConnection(info.jdbcUrl(), info.username, info.password())) {
            return c.isValid(3);
        } catch (SQLException e) {
            return false;
        }
    }

    public List<String> loadTables(ConnectionInfo info) throws SQLException {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name";
        try (Connection c = DriverManager.getConnection(info.jdbcUrl(), info.username, info.password());
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, info.database());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    public List<Map<String, Object>> importTable(ConnectionInfo info, String table) throws SQLException {
        List<Map<String, Object>> fields = new ArrayList<>();
        String sql = "SELECT column_name, data_type, is_nullable, column_default, column_comment, "
                + "character_maximum_length, numeric_precision, numeric_scale, column_key "
                + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        try (Connection c = DriverManager.getConnection(info.jdbcUrl(), info.username(), info.password());
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, info.database());
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("name", rs.getString("column_name"));
                    f.put("type", mapType(rs.getString("data_type")));
                    f.put("required", "NO".equalsIgnoreCase(rs.getString("is_nullable")));
                    f.put("primaryKey", "PRI".equalsIgnoreCase(rs.getString("column_key")));
                    f.put("unique", "UNI".equalsIgnoreCase(rs.getString("column_key"))
                            || "PRI".equalsIgnoreCase(rs.getString("column_key")));
                    if (rs.getString("character_maximum_length") != null) {
                        f.put("length", rs.getInt("character_maximum_length"));
                    }
                    if (rs.getString("numeric_precision") != null) {
                        f.put("precision", rs.getInt("numeric_precision"));
                    }
                    if (rs.getString("numeric_scale") != null) {
                        f.put("scale", rs.getInt("numeric_scale"));
                    }
                    if (rs.getString("column_default") != null) {
                        f.put("defaultValue", rs.getString("column_default"));
                    }
                    if (rs.getString("column_comment") != null && !rs.getString("column_comment").isBlank()) {
                        f.put("comment", rs.getString("column_comment"));
                    }
                    fields.add(f);
                }
            }
        }
        return fields;
    }

    private static String mapType(String mysqlType) {
        if (mysqlType == null) return "string";
        return switch (mysqlType.toLowerCase()) {
            case "varchar", "char" -> "string";
            case "text", "longtext", "mediumtext", "tinytext" -> "text";
            case "tinyint" -> "boolean";
            case "int", "integer", "mediumint", "smallint" -> "integer";
            case "bigint" -> "long";
            case "decimal", "numeric", "double", "float" -> "decimal";
            case "boolean", "bit" -> "boolean";
            case "date" -> "date";
            case "datetime", "timestamp" -> "datetime";
            default -> "string";
        };
    }
}
