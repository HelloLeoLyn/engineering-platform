package com.engineeringplatform.generator.contracts;

/**
 * Generation Operation 类型（V0.7 §20 标准 Operation 子集）。
 * Executor V1 实现 CREATE_DIRECTORY / CREATE_FILE / UPDATE_MANAGED_FILE / DELETE；
 * REGISTER_ 与 ADD_ 系列保留在 Contract（Schema enum），V1 Executor 不执行（blocked as not implemented）。
 */
public enum OperationType {
    CREATE_DIRECTORY,
    CREATE_FILE,
    UPDATE_MANAGED_FILE,
    DELETE,
    REGISTER_MODULE,
    REGISTER_PROVIDER,
    ADD_MAVEN_MODULE,
    ADD_DEPENDENCY,
    ADD_FRONTEND_PACKAGE,
    ADD_ROUTE,
    ADD_PERMISSION,
    ADD_MIGRATION,
    ADD_TEST,
    ADD_GUIDE
}
