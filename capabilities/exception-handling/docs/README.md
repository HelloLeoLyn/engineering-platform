# exception-handling — Capability Asset

统一异常响应与全局异常处理。

- depends on: web（@RestControllerAdvice 位于 web 层）
- files: ApiError.java / GlobalExceptionHandler.java（render 模板）
- 消费 validation 的校验异常（MethodArgumentNotValidException / ConstraintViolationException）
