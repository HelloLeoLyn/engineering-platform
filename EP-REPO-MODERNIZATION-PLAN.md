# EP-REPO-MODERNIZATION-001 — Reference App Self-Hosting Plan

- **日期**: 2026-08-15 ｜ **Mode**: PLANNING ONLY / READ ONLY
- **状态**: 仅审计与规划，未修改任何文件

## 1. Current Repository Reality

```
engineering-platform/
├── generator/            # ep CLI 本体（generator-core/contracts/scripts/schemas）
├── capabilities/         # 22 个资产（backend 9 + frontend 8 + infra 5）
├── registry/             # capabilities.yaml / modules.yaml 等索引
├── backend/              # V0.1 手工 Java 工程骨架（历史参照物）
├── frontend/             # 空壳（仅 apps/.gitkeep + packages/.gitkeep）
├── tests/fixtures/       # 生成器 E2E fixture（v04-reference / v05-reference）
├── scripts/              # validate.sh 等
├── docs/                 # 架构/标准/ADR/release
├── deploy/               # docker/compose.yaml + environments 占位
└── ep                    # CLI 入口
```

**关键事实**：
- `generator/` + `capabilities/` 是当前架构主体（Asset-first / Generator-first）
- `backend/` 自仓库初始化（commit a310777）后**从未再提交**，32 个 Java 文件
- `frontend/` 是纯空壳（2 个 .gitkeep）
- CI（.github/workflows/ci.yml）**不引用** backend/frontend/deploy
- scripts/ **不引用** backend/frontend
- tests/fixtures **不引用** backend

## 2. backend/ Audit

| 子目录 | 内容 | 行数/文件 | 证据 |
|---|---|---|---|
| `platform/platform-core` | RequestContext/ErrorCode/ErrorSeverity/PlatformException/IdGenerator/PageQuery/PageResult/SortDirection/SortSpec/CurrentClock/SystemCurrentClock + 2 tests | 12 java | 核心类**全部被 capabilities/platform-core 资产化**（见 §4） |
| `platform/platform-web` | RequestIdFilter/GlobalExceptionHandler/ApiResponse/PlatformHealthController/FieldValidationError/ValidationErrorDetails/WebErrorCode | 7 java | GlobalExceptionHandler→capabilities/exception-handling；ApiResponse→platform-core；HealthController→platform-core；RequestIdFilter 生成项目由 authentication 资产覆盖 |
| `platform/platform-data` | package-info 仅占位 | 1 | 空壳 |
| `platform/platform-validation` | package-info 仅占位 | 1 | 空壳 |
| `platform/platform-bom` | 依赖 BOM | 1 pom | 生成项目用 spring-boot-starter-parent，**不依赖此 BOM** |
| `modules/sample-customer` | 完整 CRUD 模块（Controller/Service/Mapper/SQL/Test） | 11 java + 1 sql | 已被 capabilities/product-reference 替代（后者更完整：DataScope+HTTP E2E） |
| `application/platform-app` | Spring Boot 装配 + SampleCustomerIntegrationTest | 3 java | 生成项目等价物已由 ep generate 产出 |
| `config/checkstyle` | checkstyle.xml | 1 | **唯一可能保留项**（见 §5） |
| `providers/` `starters/` | .gitkeep 空壳 | 0 | 空壳 |

**引用关系**：
- 4 个 generator 测试（E2EMinimalProjectTest/VerificationEngineTest/WorkModelTest/AgentExecutionTest）引用 `backend/modules/sample-customer/` 为**字符串路径**（scope/ChangeManifest 测试数据，不读真实文件；E2EMinimalProjectTest:121 是 tempDir 自建路径断言）
- registry/modules.yaml：sample-customer 条目（描述文本，已与路径解耦）
- generator/schemas/examples/：5 个 YAML 示例引用 backend/ 路径（文档样例，不参与测试运行）
- docs/：v0.2/v0.4/v0.5 scope docs + README + frontend-ux-baseline 提及（文档性，非依赖）
- README.md:17/96：backend/frontend 标注为 "V0.1 工程骨架（占位/演进中）"

## 3. frontend/ Audit

- **内容**: 仅 `apps/.gitkeep` + `packages/.gitkeep`（2 个空文件）
- **引用**: docs/frontend-ux-baseline.md 提及（文档性）；无代码/测试/CI 依赖
- **结论**: 无任何独立价值，纯占位

## 4. Asset Coverage Matrix

| backend 类 | 资产化位置 | 覆盖 |
|---|---|---|
| RequestContext | capabilities/platform-core | ✅ 等价 |
| ErrorCode | capabilities/platform-core | ✅ |
| PlatformException | capabilities/platform-core | ✅ |
| IdGenerator | capabilities/platform-core | ✅ |
| PageQuery/PageResult | capabilities/platform-core | ✅ |
| CurrentClock | capabilities/platform-core | ✅ |
| ApiResponse | capabilities/platform-core | ✅ |
| GlobalExceptionHandler | capabilities/exception-handling | ✅（生成版更完整：PlatformException→404/401/403） |
| HealthController | capabilities/platform-core | ✅ |
| SampleCustomer CRUD | capabilities/product-reference | ✅ 超集（+DataScope+E2E） |
| Auth/RBAC | capabilities/authentication + rbac | ✅ 超集 |
| ErrorSeverity | — | ⚠️ backend-only 辅助枚举，生成项目无 consumer |
| SortDirection/SortSpec | — | ⚠️ backend-only 辅助分页类型，生成项目用简单 PageQuery |
| SystemCurrentClock | — | ⚠️ backend-only，生成项目用 CurrentClock.system() |

**结论**：backend/ 的核心能力覆盖率 ≈100%（除 4 个无 consumer 的辅助类）。

## 5. Remaining Unique Value

| 项 | 唯一价值 | 建议 |
|---|---|---|
| `config/checkstyle.xml` | 平台自身代码规范基线（backend/ 内部用） | **C**（移到 docs/standards 或保留为平台自检配置，不与生成产物耦合） |
| ErrorSeverity/SortDirection/SortSpec/SystemCurrentClock | 无生成项目 consumer；如未来需要可作为资产补全 | **E**（删除候选，不迁移） |
| sample-customer 模块 | 已被 product-reference 完全替代（后者是更完整的 Reference Asset） | **E/C**（作为历史示例归档，不迁移模板） |
| platform-app | 生成项目等价 | **E** |
| platform-bom | 生成项目不依赖 | **E** |

## 6. Target Repository Structure

```
engineering-platform/
├── generator/                 # 保持不变
├── capabilities/              # 保持不变（22 资产）
├── registry/                  # 保持不变
├── schemas/                   # 保持不变（示例路径引用待更新）
├── scripts/                   # 保持不变
├── tests/                     # fixture 路径引用待更新（字符串）
├── docs/                      # README/scope docs 引用待更新
├── examples/                  # 新增
│   └── enterprise-reference/
│       └── project.yaml       # 自举 manifest
└── target/                    # 生成输出（gitignored）
    └── reference-app/
        ├── backend/
        └── frontend/
```

## 7. Self-Hosting Design

```
examples/enterprise-reference/project.yaml
  → ./ep validate      (exit 0)
  → ./ep resolve       (资产闭包解析)
  → ./ep generate --output target/reference-app
  → target/reference-app/
      ├── backend/   (Spring Boot, 由 capabilities 生成)
      └── frontend/  (Vue3, 由 frontend-* 资产生成)
  → backend: mvn test / mvn package
  → frontend: pnpm test / pnpm build
  → scripts/dev-start.sh → manual acceptance
```

- **不新建生成机制**：完全复用现有 Resolver/EPM/Generator/Executor/Conformance
- **manifest**：`capabilities: [product-reference]`（单声明自动补齐全部企业能力，已验证 WORK-005）
- **证据**：当前 V0.5 fresh project（/tmp/v05-w6-fresh）已是该 flow 的等价产物（198 源码文件、13.4k 行、全测试绿）

## 8. Migration Phases

| Phase | 动作 | Gate |
|---|---|---|
| **P1** | 建立 `examples/enterprise-reference/project.yaml` | ep validate/resolve/generate 全过 |
| **P2** | 生成 `target/reference-app`，与 backend/sample-customer + frontend 能力对比 | 生成产物功能 ⊇ 旧结构；build/test 全绿 |
| **P3** | 迁移唯一有价值内容到 Assets（若需：checkstyle 规范文档化；4 个辅助类按需资产化或明确废弃） | 无唯一代码残留 |
| **P4** | 移除 backend/frontend 旧结构引用（4 个测试字符串路径、5 个 schema examples、README/docs、registry/modules.yaml 描述） | 全仓 grep `backend/` 仅剩 docs 历史说明 |
| **P5** | 删除/降级 legacy 目录（backend/ frontend/ 移入 docs/legacy 说明或直接删除） | 删除后全量测试绿 |

## 9. Compatibility Risks

| 风险 | 等级 | 缓解 |
|---|---|---|
| 4 个 generator 测试字符串引用 backend/ 路径 | 低（不读文件，已验证删除后 90 tests 绿） | P4 更新为通用路径（如 `modules/sample-*/`） |
| schema examples 引用 backend/ 路径 | 低（文档样例） | P4 更新示例 |
| README/docs 引用 backend/frontend 为"工程骨架" | 低（文档性） | P4 更新为 examples/enterprise-reference 指向 |
| V0.1~V0.5 compatibility | **中**：历史 fixture（v04-reference）不依赖 backend，但需确认所有历史测试不隐式引用 | P5 前跑一次历史 fixture 生成验证 |
| checkstyle 若删除影响平台自检 | 低 | P3 决定保留为平台自检配置或文档化 |

## 10. Deletion Preconditions

删除 backend/ + frontend/ 前必须全部满足：

- [ ] 1. 无唯一代码未资产化（ErrorSeverity 等 4 类明确废弃或资产化）
- [ ] 2. CI 无引用（✅ 已确认无）
- [ ] 3. docs 引用已更新（README/scope docs/ADR）
- [ ] 4. tests fixture 依赖已移除（4 个测试字符串路径更新）
- [ ] 5. platform-app 无独有能力（✅ 生成项目等价）
- [ ] 6. V0.1~V0.5 compatibility 验证通过（历史 fixture 生成/测试）
- [ ] 7. examples/enterprise-reference 自举 flow 已证明等价/更好

## 11. Recommended First Implementation Task

**Phase 1（最小、零风险）**：
1. 创建 `examples/enterprise-reference/project.yaml`（单声明 product-reference）
2. `./ep validate / resolve / generate --output target/reference-app`
3. 生成项目 backend mvn test + frontend pnpm test/build
4. 输出 Self-Hosting 可行性证明（P2 对比表）

> 此任务不触碰 backend/frontend 目录、不改 Core Engine、不跑 Full Regression。

## 12. GO / NO-GO

**GO（有条件）**

理由：
- backend/ 核心能力 ≈100% 已被 capabilities 资产化（唯一例外是 4 个无 consumer 辅助类）
- frontend/ 是纯空壳
- CI/scripts/tests-fixtures 无真实依赖（仅 4 个测试字符串 + 文档引用，低风险）
- Self-Hosting flow 已在 V0.5 实际验证等价（当前 fresh project 即产物）

条件：
1. 必须先走 P1→P2（证明生成产物 ⊇ 旧结构）再动 backend/frontend
2. P4 引用清理与 P5 删除必须分阶段，每阶段独立验证
3. V0.5 RELEASE_GATE 完成后再执行本计划（当前优先 V0.5 验收收尾）

**本计划未修改任何文件**；等待批准后按 Phase 1 开始实施。
