# Engineering Platform Agent Rules

## Repository Purpose

This repository contains the Engineering Platform.
It is separate from AI Dev OS.

AI Dev OS orchestrates engineering work.
Engineering Platform provides reusable application architecture, capabilities,
providers, modules, generator assets, schemas, and engineering standards.

## Core Principles

1. Stable Core
2. Default does not mean fixed
3. Depend on capability contracts, not concrete providers
4. Business modules must not depend on provider implementations
5. Generator output must be deterministic
6. Runtime secrets must never be stored in manifests
7. User-owned files must not be overwritten by generators
8. Released database migrations are immutable
9. Scope expansion requires explicit planning
10. Fix generator defects in the generator, not repeatedly in generated projects

## Dependency Direction

Application -> Module -> Capability Contract -> Platform Core
Provider -> Capability Contract

Forbidden:
- Business Module -> Concrete Provider
- Platform Core -> Business Module
- Capability Contract -> Concrete Provider

## Generator Ownership

- GENERATED: generator fully owns the file
- MANAGED: controlled structured updates only
- USER_OWNED: initial skeleton only, never overwrite user implementation
- IMMUTABLE: released assets/migrations must not be modified

## Safety

Forbidden by default:
- git reset --hard
- git clean -fd
- force push
- destructive migration
- deleting user-owned files
- overwriting released migrations

## Backend Quality Commands

Run from `backend/`.

```bash
./mvnw spotless:apply
./mvnw spotless:check
./mvnw clean verify
```

Do not bypass Maven Enforcer, Checkstyle, Spotless, ArchUnit, or tests.
If generated code breaks these checks, treat it as a generator/template defect.
