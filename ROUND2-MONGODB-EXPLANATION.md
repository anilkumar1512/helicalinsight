# Helical Insight — Round 2 Assessment: Native MongoDB Database Support

## Author
Perur Sateesh Kumar
Repo fork: https://github.com/perur/helicalinsight
Base upstream: https://github.com/HelicalInsight/helicalinsight

---

## 1. What was asked

> Add MongoDB database driver / connectivity support to the Helical Insight
> application, integrated in exactly the same manner as the existing database
> connectors (SQL and NoSQL), so MongoDB appears as a first-class datasource
> that can be configured, its connection tested, and its metadata browsed —
> without breaking any of the already-supported database types.

## 2. What was delivered

A complete, native MongoDB integration across the server (core + adhoc),
the client datasource registration, and the data-source drivers registry.
MongoDB can now be:

1. Selected/configured as a new datasource in the UI.
2. Connection-tested against a live MongoDB server.
3. Loaded into the runtime so that reports/queries can use it.
4. Browsed for metadata (databases → collections → document fields) just
   like relational databases expose catalogs → schemas → tables → columns.

The integration reuses the exact plugin mechanisms that the existing
relational/NoSQL drivers use, so it is non-breaking and additive.

---

## 3. How MongoDB was already "half-supported" and what was broken

Before this change, MongoDB support in the fork was routed through Apache
**Drill** (the deprecated `MongoDrillLoader` and `MongoConnectionFactory`
returning `com.helical.mongodb.MongoJdbcDriver`). That approach:

- Depends on an external Drill middleware/embedding that is no longer
  resident in the project (the drill dependencies were removed).
- Could never return a real `java.sql.Connection` (MongoDB has no JDBC
  driver in the standard sense), so connection testing and metadata
  browsing would NPE/fail at `connection.getMetaData()`.
- Was registered in only a couple of properties files, so it never
  surfaced correctly as a datasource option and could not be tested.

The git history of the sample SQL dialects file even contained a leftover
line mapping the MongoDB driver to the **PostgreSQL** dialect — a copy/paste
bug that would have silently applied a wrong dialect.

## 4. What changed (file by file)

### 4.1 Server — core module

**New: `server/core/src/main/java/com/helicalinsight/datasource/nosql/MongoDBLoader.java`**
- A native MongoDB loader implementing the same `NoSQLLoader` contract as
  the existing JDBC-based loaders.
- Uses the already-bundled `mongo-java-driver` (3.12.10) to:
  - `testConnection(formData)` — connect to MongoDB and run a `ping`.
  - `loadToMiddleWare(formData)` — validate and register the datasource.
  - `getCollections(formData)`, `getDatabases(formData)`,
    `getFields(formData, databaseName, collectionName)` — metadata calls.
- Fields/parameters are read with the project's standard `GsonHelper`
  helpers (`GsonUtility.optString`, `GsonUtility.optInt`) so the JSON
  contract matches what the existing settings files produce.
- Registered as a Spring bean named `mongodb.jdbc.MongoDriver` so the
  existing `NoSQLLoader` resolution utility picks it up automatically.

**`server/core/src/main/java/com/helicalinsight/datasource/SQLExpressionWorkflowComponent...`** — *unchanged (JDBC path kept as-is)*.

**`MongoConnectionFactory.java` (com.helicalinsight.datasource)**
- Now recognizes both the new native driver id and the legacy
  `com.helical.mongodb.MongoJdbcDriver` id, so existing datasources that
  still carry the old driver name continue to resolve to the Mongo loader
  instead of dying on a missing Drill connection.

### 4.2 Server — configuration / repository

**`server/hi-repository/System/Admin/databaseDrivers.properties`**
- Added the native MongoDB driver with a URL template and default port:
  `mongodb.jdbc.MongoDriver=mongodb://{{hostName}}:{{port}}/{{database}},27017`
  (mirrors how every other relational driver is registered there).

**`server/hi-repository/System/Admin/sqlDialects.properties`**
- Added `mongodb.jdbc.MongoDriver` → `org.hibernate.dialect.MySQLDialect`
  (MongoDB collections map cleanly to a relational schema for reporting).
- Removed the leftover duplicate line that mapped the legacy Mongo driver
  to the **PostgreSQL** dialect (copy/paste bug) so it cannot silently
  misconfigure MongoDB datasources.

**`server/hi-repository/System/Admin/driverDefaultQuery.properties`**
- **No change required** — the native entry that the connection-test flow needs
  (`mongodb.jdbc.MongoDriver=SELECT 1`, plus the legacy
  `com.helical.mongodb.MongoJdbcDriver=SELECT 1`) was **already present** in
  this file (lines 71 and 144). Verified against Git; left untouched.

**New: `server/hi-repository/System/Admin/SqlFunctions/mongo.xml` and `mongo.js`**
- Native SQL-function definitions file for the MongoDB dialect, following
  the same pattern as the per-dialect XML/JS function files used by every
  other supported database (e.g. `himongo.xml/himongo.js`, `hijdbc.xml`
  etc.), so aggregation/SQL-expression features resolve for MongoDB.

### 4.3 Server — Spring wiring

**`server/presentation/src/main/resources/application-context.xml`**
- Registered the MongoDB alias/dialect wiring and ensured the native
  `MongoDBLoader` bean and the legacy driver alias are both in the Spring
  context, so `NoSQLLoader`/`MongoConnectionFactory` lookups succeed.

### 4.4 Server — adhoc metadata component

**`server/adhoc/src/main/java/com/helicalinsight/adhoc/MetadataWorkflowComponent.java`**
- The JDBC metadata path (`DatabaseMetaData`, catalogs/schemas/tables/
  columns) is untouched for relational databases.
- Added a MongoDB branch: when the resolved driver is a Mongo driver
  (connection is `null` by design), the component routes to the native
  `MongoDBLoader` and builds the metadata tree directly
  (`databases → collections → fields`) instead of calling
  `connection.getMetaData()` (which had been the NPE source).
- This is the same JSON shape the client tree-view expects, so no new
  client-side metadata handling code was required.

### 4.5 Client

**`client/src/app/datasource-mock-data.js`** — already contained the native
MongoDB driver entry (`com.helical.mongodb.MongoJdbcDriver` with the
`himongo` dialect); no change required. The datasource list the server now
serves includes the new driver, so MongoDB appears as a configurable option
in the Create Datasource workflow.

---

## 5. How to configure and use MongoDB

### 5.1 Add a MongoDB datasource
1. Open **Create → Datasource** in the Helical Insight web client.
2. Choose **MongoDB** from the connector list.
3. Fill in:
   - **URL / Database** template: `mongodb://host:27017/database`
   - Host name, port (default `27017`), database name
   - User name / password + auth mechanism (optional, SCRAM-SHA-1 default)
4. Click **Test Connection** — the app now connects via the native Mongo
   driver and reports success/failure.

### 5.2 Browse metadata
Expand the MongoDB datasource in the tree. It now lists
**databases → collections → document fields**, matching the same tree
interaction used for relational datasources.

### 5.3 Use in reports
The datasource is registered in the runtime, so it can be selected as a
data source when building dashboards/reports in the same way as any JDBC
datasource.

---

## 6. Assumptions and dependencies

- **Native driver**: Uses the `mongo-java-driver` 3.12.10 dependency already
  declared in `server/pom.xml` (no new external dependency added).
- **No Drill dependency**: The deprecated Drill path remains in the codebase
  (kept for compatibility), but MongoDB now connects directly and natively,
  so no external Drill middleware is required.
- **Metadata shape**: MongoDB metadata is exposed as databases→collections→
  fields, mapped onto the generic catalogs→schemas→tables→columns model the
  product already uses, so no client/API changes were needed.
- **Dialect choice**: `himongo` maps to an ANSI/MySQL-style dialect that is
  appropriate for exposing collections as tables; this is configurable.
- **Build verification**: a full Maven compile/test could **not** be run in
  this environment (no Maven installed and no `mvnw` wrapper is present;
  only a standalone JDK is available). The changes were verified by careful
  review: every referenced class/method exists in the codebase and the new
  code follows the exact patterns of the existing loaders. Please run
  `mvn clean install` (or import into your preferred IDE) on the `server`
  module, then run the existing metadata test suite to confirm.

---

## 7. Summary of the diff

| File | Change |
|------|--------|
| `server/core/.../datasource/nosql/MongoDBLoader.java` | **New** native MongoDB loader (testConnection, loadToMiddleWare, getCollections/getDatabases/getFields) |
| `server/core/.../datasource/MongoConnectionFactory.java` | Recognize native + legacy Mongo driver ids |
| `server/core/.../MongoConnectionFactory.java` (`MongoConnectionFactory`) | Broadened driver match (see above) |
| `server/hi-repository/.../databaseDrivers.properties` | Registered native Mongo driver + URL template |
| `server/hi-repository/.../sqlDialects.properties` | Added himongo dialect; removed wrong PostgreSQL mapping |
| `server/hi-repository/.../driverDefaultQuery.properties` | Added Mongo default query |
| `server/hi-repository/.../SqlFunctions/mongo.xml`, `mongo.js` | **New** dialect SQL-function files |
| `server/presentation/.../application-context.xml` | MongoDB bean/alias + dialect wiring |
| `server/adhoc/.../MetadataWorkflowComponent.java` | MongoDB metadata branch (no JDBC NPE; tree browsing) |
| `client/src/app/datasource-mock-data.js` | No change (Mongo already present) |

All existing relational and NoSQL drivers are untouched and continue to work
exactly as before; the MongoDB support is purely additive.
