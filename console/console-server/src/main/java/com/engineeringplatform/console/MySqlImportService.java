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
 * MySQL Schema Import (V06-WORK-005) + V07-WORK-005 Metadata Discovery.
 *
 * Reads information_schema.tables / columns / statistics / key_column_usage /
 * referential_constraints via JDBC and maps tables into Business Module
 * Contract fields. V07-WORK-005 adds multi-table discovery with:
 *   - unique index detection (information_schema.statistics)
 *   - real foreign keys (key_column_usage + referential_constraints)
 *   - referenced table / referenced column
 * Password is used ONLY for the JDBC connection — never written to contract,
 * candidates, logs, or generated files.
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

    // ------------------------------------------------------------------
    // V07-WORK-005 — multi-table metadata discovery
    // ------------------------------------------------------------------

    /** Full metadata for one table: columns + unique indexes + foreign keys. */
    public static final class TableMeta {
        public final String table;
        public final String comment;
        public final List<Map<String, Object>> columns;
        public final List<Map<String, Object>> uniqueIndexes;
        public final List<Map<String, Object>> foreignKeys;

        TableMeta(String table, String comment, List<Map<String, Object>> columns,
                  List<Map<String, Object>> uniqueIndexes, List<Map<String, Object>> foreignKeys) {
            this.table = table;
            this.comment = comment;
            this.columns = columns;
            this.uniqueIndexes = uniqueIndexes;
            this.foreignKeys = foreignKeys;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", table);
            m.put("comment", comment);
            m.put("columns", columns);
            m.put("uniqueIndexes", uniqueIndexes);
            m.put("foreignKeys", foreignKeys);
            return m;
        }
    }

    /**
     * Discover metadata for the given tables (information_schema.tables +
     * columns + statistics + key_column_usage + referential_constraints).
     */
    public List<TableMeta> discover(ConnectionInfo info, List<String> tables) throws SQLException {
        List<TableMeta> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(info.jdbcUrl(), info.username, info.password())) {
            for (String table : tables) {
                out.add(discoverOne(c, info.database(), table));
            }
        }
        return out;
    }

    private TableMeta discoverOne(Connection c, String schema, String table) throws SQLException {
        String comment = "";
        String sqlTable = "SELECT table_comment FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_name = ?";
        try (PreparedStatement ps = c.prepareStatement(sqlTable)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) comment = rs.getString(1) == null ? "" : rs.getString(1);
            }
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        String sqlCols = "SELECT column_name, data_type, is_nullable, column_default, column_comment, "
                + "character_maximum_length, numeric_precision, numeric_scale, column_key, ordinal_position "
                + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? "
                + "ORDER BY ordinal_position";
        try (PreparedStatement ps = c.prepareStatement(sqlCols)) {
            ps.setString(1, schema);
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
                    columns.add(f);
                }
            }
        }

        List<Map<String, Object>> uniqueIndexes = new ArrayList<>();
        String sqlIdx = "SELECT DISTINCT index_name, non_unique FROM information_schema.statistics "
                + "WHERE table_schema = ? AND table_name = ? AND index_name <> 'PRIMARY'";
        try (PreparedStatement ps = c.prepareStatement(sqlIdx)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int nonUnique = rs.getInt("non_unique");
                    if (nonUnique != 0) continue; // only unique indexes → DB unique fact
                    Map<String, Object> ix = new LinkedHashMap<>();
                    ix.put("name", rs.getString("index_name"));
                    ix.put("unique", true);
                    uniqueIndexes.add(ix);
                }
            }
        }

        List<Map<String, Object>> foreignKeys = new ArrayList<>();
        String sqlFk = "SELECT k.constraint_name, k.column_name, k.ordinal_position, "
                + "k.referenced_table_name, k.referenced_column_name, "
                + "r.update_rule, r.delete_rule "
                + "FROM information_schema.key_column_usage k "
                + "LEFT JOIN information_schema.referential_constraints r "
                + "  ON r.constraint_schema = k.constraint_schema "
                + " AND r.constraint_name = k.constraint_name "
                + " AND r.table_name = k.table_name "
                + "WHERE k.table_schema = ? AND k.table_name = ? "
                + "  AND k.referenced_table_name IS NOT NULL "
                + "ORDER BY k.constraint_name, k.ordinal_position";
        try (PreparedStatement ps = c.prepareStatement(sqlFk)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> fk = new LinkedHashMap<>();
                    fk.put("constraintName", rs.getString("constraint_name"));
                    fk.put("column", rs.getString("column_name"));
                    fk.put("referencedTable", rs.getString("referenced_table_name"));
                    fk.put("referencedColumn", rs.getString("referenced_column_name"));
                    if (rs.getString("update_rule") != null) fk.put("updateRule", rs.getString("update_rule"));
                    if (rs.getString("delete_rule") != null) fk.put("deleteRule", rs.getString("delete_rule"));
                    foreignKeys.add(fk);
                }
            }
        }

        return new TableMeta(table, comment, columns, uniqueIndexes, foreignKeys);
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

    /** Dummy reference to keep the import stable across JDK builds. */
    @SuppressWarnings("unused")
    private static final byte[] UTF8 = StandardCharsets.UTF_8.encode("x").array();
}
