package org.katacr.kaproxy.lastseen;

import org.katacr.kaproxy.config.KaProxyConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * MySQL 实现：读写后端 KaLogin 创建的 {@code kalogin_lastseen} 表。
 *
 * <p>所有 JDBC 操作在专用单线程执行器上运行，避免阻塞代理事件线程；
 * 连接失效时自动重建。未配置数据库时所有操作安全降级为空操作。</p>
 */
public final class MySqlLastSeenRepository implements LastSeenRepository {
    private final String url;
    private final String username;
    private final String password;
    private final Consumer<String> logError;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "KaProxy-LastSeen-DB");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Connection connection;
    private static final boolean DRIVER_LOADED = loadDriver();

    /**
     * 显式加载随插件打包的 MySQL 驱动。
     *
     * <p>代理的插件类加载器隔离使 JDBC 的 ServiceLoader（基于线程上下文类加载器）
     * 看不到插件内的驱动，因此必须用本类的类加载器主动注册，否则报
     * {@code No suitable driver found}。</p>
     */
    private static boolean loadDriver() {
        try {
            Class.forName("org.katacr.kaproxy.libs.mysql.cj.jdbc.Driver");
            return true;
        } catch (ClassNotFoundException error) {
            return false;
        }
    }

    /** 依据配置创建存储；未配置数据库时 url 为 null。 */
    public MySqlLastSeenRepository(KaProxyConfig config, Consumer<String> logError) {
        this.url = config.databaseUrl();
        this.username = config.databaseUsername();
        this.password = config.databasePassword();
        this.logError = logError;
        if (this.url != null && !DRIVER_LOADED) {
            log("未找到内置 MySQL 驱动，无法连接数据库");
        }
    }

    /** 是否已配置数据库。 */
    public boolean configured() {
        return url != null;
    }

    @Override
    public CompletableFuture<Optional<Record>> load(UUID playerId) {
        if (url == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT server, world, x, y, z, yaw, pitch FROM kalogin_lastseen WHERE uuid = ?";
            try (PreparedStatement statement = connection().prepareStatement(sql)) {
                statement.setString(1, playerId.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<Record>empty();
                    }
                    String server = rs.getString(1);
                    String world = rs.getString(2);
                    if (server == null || server.isBlank() || world == null) {
                        return Optional.<Record>empty();
                    }
                    return Optional.of(new Record(server, world,
                            rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                            rs.getFloat(6), rs.getFloat(7)));
                }
            } catch (SQLException error) {
                throw new IllegalStateException(error);
            }
        }, executor);
    }

    @Override
    public void saveServer(UUID playerId, String server) {
        if (url == null || server == null || server.isBlank()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            // upsert：后端坐标写入可能尚未执行，行不存在时先建行（坐标由后端补全）
            String sql = "INSERT INTO kalogin_lastseen (uuid, server) VALUES (?, ?) " +
                    "ON DUPLICATE KEY UPDATE server = VALUES(server)";
            try (PreparedStatement statement = connection().prepareStatement(sql)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, server);
                statement.executeUpdate();
            } catch (SQLException error) {
                throw new IllegalStateException(error);
            }
        }, executor).exceptionally(error -> {
            log("写入上次位置失败: " + error.getMessage());
            return null;
        });
    }

    @Override
    public void delete(UUID playerId) {
        if (url == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM kalogin_lastseen WHERE uuid = ?";
            try (PreparedStatement statement = connection().prepareStatement(sql)) {
                statement.setString(1, playerId.toString());
                statement.executeUpdate();
            } catch (SQLException error) {
                throw new IllegalStateException(error);
            }
        }, executor).exceptionally(error -> {
            log("删除上次位置失败: " + error.getMessage());
            return null;
        });
    }

    @Override
    public void close() {
        Connection current = connection;
        connection = null;
        if (current != null) {
            try {
                current.close();
            } catch (SQLException ignored) {
                // 关闭失败无需处理
            }
        }
        executor.shutdown();
    }

    /** 返回可用连接，失效时重建。 */
    private Connection connection() throws SQLException {
        Connection current = connection;
        if (current != null && !current.isClosed() && current.isValid(2)) {
            return current;
        }
        synchronized (this) {
            if (connection != null && !connection.isClosed() && connection.isValid(2)) {
                return connection;
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // 忽略旧连接关闭失败
                }
            }
            connection = DriverManager.getConnection(url, username, password);
            return connection;
        }
    }

    private void log(String message) {
        if (logError != null) {
            logError.accept(message);
        }
    }
}
