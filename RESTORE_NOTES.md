# 恢复说明

这个包用于恢复从 **Engineering Platform V0.1 Repository Bootstrap** 开始已经搭建的基础代码。

## 建议恢复路径

```bash
cd /home/administrator/workspace
unzip engineering-platform-v0.1-bootstrap.zip
mv engineering-platform-v0.1-bootstrap engineering-platform
cd engineering-platform
```

如果 `/home/administrator/workspace/engineering-platform` 已经存在，先不要直接覆盖；把现有目录改名备份后再放入本包。

## 初始化 Git

```bash
git init
git branch -m main
git add .
git commit -m "chore: restore engineering platform v0.1 bootstrap"
```

## 后端构建

```bash
cd backend
mvn wrapper:wrapper
./mvnw spotless:apply
./mvnw clean verify
```

`clean verify` 中的 MySQL 集成测试使用 Testcontainers，因此 Docker 必须可用：

```bash
docker ps
```

## 手工启动应用

```bash
cd ../deploy/docker
docker compose up -d
cd ../../backend
./mvnw -pl application/platform-app -am spring-boot:run
```

验证：

```bash
curl http://localhost:8080/api/platform/ping
curl http://localhost:8080/v3/api-docs
```

创建 Sample Customer：

```bash
curl -X POST http://localhost:8080/api/sample/customers \
  -H 'Content-Type: application/json' \
  -d '{"customerCode":"CUST-001","customerName":"Sample Customer"}'
```

查询：

```bash
curl http://localhost:8080/api/sample/customers
```

## 接下来的原计划

下一阶段原本准备开始 Frontend V0.1 Shell：Node 24、pnpm workspace、Vue 3、TypeScript Strict、Vite、Pinia、Vue Router、Element Plus、ESLint、Prettier、Vitest，并接 Sample Customer 页面。
