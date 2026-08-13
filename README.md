# Engineering Platform V0.1 Bootstrap

这是根据已完成的 Engineering Platform V0.1 Repository Bootstrap 阶段重新整理的基础包。

当前包含：

- Repository Skeleton
- `platform.yaml` / Registry / Schema 占位
- Maven Multi-Module Backend
- Java 25 / Spring Boot 4.1 基线
- platform-core / platform-web / platform-validation / platform-data
- Maven Enforcer / Spotless / Checkstyle / ArchUnit
- Foundation API：Error / Paging / Clock / Id / RequestContext / ApiResponse
- RequestId Filter / GlobalExceptionHandler
- OpenAPI / Flyway / MySQL / MyBatis-Plus / MapStruct
- `sample-customer` CRUD 示例模块
- Testcontainers MySQL 集成测试

## 建议放置位置

```bash
/home/administrator/workspace/engineering-platform
```

## 使用方式

如果目标目录不存在：

```bash
cd /home/administrator/workspace
unzip engineering-platform-v0.1-bootstrap.zip
mv engineering-platform-v0.1-bootstrap engineering-platform
cd engineering-platform
```

然后初始化 Git（如果需要）：

```bash
git init
git branch -m main
git add .
git commit -m "chore: restore engineering platform v0.1 bootstrap"
```

## 后端验证

要求 Java 25、Maven 3.9+、Docker 可用。

```bash
cd backend
mvn wrapper:wrapper
./mvnw spotless:apply
./mvnw clean verify
```

> 如果你当前环境还没生成 Maven Wrapper，先执行 `mvn wrapper:wrapper`。

## 本地启动

先启动 MySQL：

```bash
docker run -d \
  --name engineering-platform-mysql \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=engineering_platform \
  -p 3306:3306 \
  mysql:8.4
```

然后：

```bash
cd backend
./mvnw -pl application/platform-app -am spring-boot:run
```

API：

```text
GET  /api/platform/ping
GET  /api/sample/customers
POST /api/sample/customers
GET  /v3/api-docs
GET  /swagger-ui.html
GET  /actuator/health
```

## 当前刻意保留的 Bootstrap 技术债

`sample-customer` 当前使用 `AtomicLong` 和 `LocalDateTime.now()`，这是为了先验证完整 CRUD 纵向链路。下一阶段应替换为平台的 `IdGenerator` 和 `CurrentClock` Provider。
