package com.helicalinsight.datasource.nosql;

import com.google.gson.JsonObject;
import com.helicalinsight.datasource.GsonUtility;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Native MongoDB connectivity loader using the bundled mongo-java-driver.
 * <p>
 * Replaces the deprecated Drill-based {@link MongoDrillLoader} for direct
 * MongoDB connections. Registers as a Spring bean under the driver class
 * name {@code mongodb.jdbc.MongoDriver} so that
 * {@link com.helicalinsight.efw.utility.NoSqlUtils#getNoSqlImplementation(String)}
 * resolves this loader for connection testing and middleware loading.
 * </p>
 * <p>
 * An alias {@code com.helical.mongodb.MongoJdbcDriver} is configured in
 * {@code application-context.xml} to also route to this loader.
 * </p>
 *
 * @author Helical Insight
 */
@Component("mongodb.jdbc.MongoDriver")
public class MongoDBLoader extends NoSQLLoader {

    private static final Logger logger = LoggerFactory.getLogger(MongoDBLoader.class);

    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int PORT_DEFAULT = 27017;

    /**
     * Tests connectivity to a MongoDB instance using the native Java driver.
     *
     * @param formData JSON containing connection parameters:
     *                 {@code jdbcUrl}, {@code host}, {@code port},
     *                 {@code database}, {@code userName}, {@code password},
     *                 {@code authMechanism}
     * @return true if the ping command succeeds, false otherwise
     */
    @Override
    public boolean testConnection(JsonObject formData) {
        logger.info("Testing MongoDB connection...");
        MongoClient client = null;
        try {
            String connectionString = buildConnectionString(formData);
            logger.debug("Connecting to MongoDB with URI: {}", connectionString);

            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(connectionString))
                    .applyToSocketSettings(builder ->
                            builder.connectTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                    .readTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    .applyToClusterSettings(builder ->
                            builder.serverSelectionTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    .build();

            client = MongoClients.create(settings);

            String database = getDatabaseName(formData);
            MongoDatabase db;
            if (database != null && !database.isEmpty()) {
                db = client.getDatabase(database);
            } else {
                db = client.getDatabase("admin");
            }

            Document result = db.runCommand(new Document("ping", 1));
            boolean success = result.getDouble("ok") != null && result.getDouble("ok") == 1.0;
            if (success) {
                logger.info("MongoDB connection successful to database: {}", db.getName());
            }
            return success;
        } catch (Exception e) {
            logger.error("MongoDB connection test failed: {}", e.getMessage());
            return false;
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    /**
     * Loads connection configuration into the middleware.
     * For direct MongoDB connectivity, this validates the connection
     * and logs the registration. Returns true to indicate success.
     *
     * @param formData JSON containing connection parameters
     * @return true if the connection is validated and loaded
     */
    @Override
    public boolean loadToMiddleWare(JsonObject formData) {
        logger.info("Loading MongoDB connection to middleware...");
        try {
            boolean connected = testConnection(formData);
            if (!connected) {
                logger.error("Failed to load MongoDB connection: connection test failed");
                return false;
            }
            String database = getDatabaseName(formData);
            String dataSourceName = GsonUtility.optString(formData, "name");
            logger.info("MongoDB connection registered successfully: dataSource={}, database={}",
                    dataSourceName, database);
            return true;
        } catch (Exception e) {
            logger.error("Error loading MongoDB connection: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Lists all collection names in the specified MongoDB database.
     *
     * @param formData JSON containing connection parameters
     * @return list of collection names, empty list on error
     */
    public List<String> getCollections(JsonObject formData) {
        List<String> collections = new ArrayList<>();
        MongoClient client = null;
        try {
            String connectionString = buildConnectionString(formData);
            client = MongoClients.create(connectionString);
            String database = getDatabaseName(formData);
            if (database != null && !database.isEmpty()) {
                MongoDatabase db = client.getDatabase(database);
                for (String name : db.listCollectionNames()) {
                    collections.add(name);
                }
            }
        } catch (Exception e) {
            logger.error("Error listing MongoDB collections: {}", e.getMessage());
        } finally {
            if (client != null) {
                client.close();
            }
        }
        return collections;
    }

    /**
     * Lists all database names on the MongoDB server.
     *
     * @param formData JSON containing connection parameters
     * @return list of database names, empty list on error
     */
    public List<String> getDatabases(JsonObject formData) {
        List<String> databases = new ArrayList<>();
        MongoClient client = null;
        try {
            String connectionString = buildConnectionString(formData);
            client = MongoClients.create(connectionString);
            for (String name : client.listDatabaseNames()) {
                databases.add(name);
            }
        } catch (Exception e) {
            logger.error("Error listing MongoDB databases: {}", e.getMessage());
        } finally {
            if (client != null) {
                client.close();
            }
        }
        return databases;
    }

    /**
     * Retrieves the field names (document keys) for a collection.
     * Uses a sample document to infer the schema.
     *
     * @param formData     JSON containing connection parameters
     * @param databaseName database name
     * @param collectionName collection name
     * @return list of field names
     */
    public List<String> getFields(JsonObject formData, String databaseName, String collectionName) {
        List<String> fields = new ArrayList<>();
        MongoClient client = null;
        try {
            String connectionString = buildConnectionString(formData);
            client = MongoClients.create(connectionString);
            MongoDatabase db = client.getDatabase(databaseName);
            com.mongodb.client.MongoCollection<Document> collection = db.getCollection(collectionName);
            Document first = collection.find().first();
            if (first != null) {
                for (String key : first.keySet()) {
                    fields.add(key);
                }
            }
        } catch (Exception e) {
            logger.error("Error retrieving MongoDB fields for {}.{}: {}",
                    databaseName, collectionName, e.getMessage());
        } finally {
            if (client != null) {
                client.close();
            }
        }
        return fields;
    }

    /**
     * Builds a MongoDB connection URI from form data parameters.
     * Supports both a full {@code jdbcUrl}/connection string and
     * individual {@code host}/{@code port}/{@code database}/{@code userName}/{@code password} fields.
     *
     * @param formData JSON with connection parameters
     * @return MongoDB connection URI string
     */
    private String buildConnectionString(JsonObject formData) {
        String jdbcUrl = GsonUtility.optString(formData, "jdbcUrl");

        if (jdbcUrl != null && !jdbcUrl.isEmpty()) {
            return jdbcUrl;
        }

        String host = GsonUtility.optString(formData, "host");
        if (host == null || host.isEmpty()) {
            host = "localhost";
        }

        int port = GsonUtility.optInt(formData, "port");
        if (port <= 0) {
            port = PORT_DEFAULT;
        }

        String userName = GsonUtility.optString(formData, "userName");
        String password = GsonUtility.optString(formData, "password");
        String database = getDatabaseName(formData);

        StringBuilder sb = new StringBuilder("mongodb://");

        if (userName != null && !userName.isEmpty() && password != null && !password.isEmpty()) {
            sb.append(userName).append(":").append(password).append("@");
        }

        sb.append(host).append(":").append(port);

        if (database != null && !database.isEmpty()) {
            sb.append("/").append(database);
        }

        String authMechanism = GsonUtility.optString(formData, "authMechanism");
        if (authMechanism != null && !authMechanism.isEmpty() && userName != null && !userName.isEmpty()) {
            sb.append("?authMechanism=").append(authMechanism);
        }

        return sb.toString();
    }

    /**
     * Extracts the database name from form data.
     * Checks {@code database} first, then {@code databaseName}.
     *
     * @param formData JSON with connection parameters
     * @return database name or null
     */
    private String getDatabaseName(JsonObject formData) {
        String database = GsonUtility.optString(formData, "database");
        if (database == null || database.isEmpty()) {
            database = GsonUtility.optString(formData, "databaseName");
        }
        if (database == null || database.isEmpty()) {
            database = GsonUtility.optString(formData, "collection");
        }
        return database;
    }
}
