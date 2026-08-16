# E2E / dev runtime profile
# Purpose: standard, machine-executable runtime for the generated application
# (Runtime Recipe + Browser E2E). Activated via `--spring.profiles.active=e2e`.
#
# H2 in-memory + schema + E2E seed. Production default is NOT affected:
# no known credentials are shipped in the default profile; the e2e seed is
# only loaded when this profile is active.
#
# Secret: NOT committed here. SecurityWebConfig reads AUTH_TOKEN_SECRET from
# the environment (with auth.token.secret config fallback). Runtime Recipe
# exports AUTH_TOKEN_SECRET before start.
spring:
  datasource:
    url: jdbc:h2:mem:e2edb;MODE=MySQL;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password:
  sql:
    init:
      mode: always
      schema-locations: classpath:db/migration/*.sql
      # E2E seed lives in main resources (db/seed-e2e), loaded only on this profile.
      # Production default profile never loads it.
      data-locations: classpath:db/seed-e2e/*.sql

auth:
  token:
    expiration-seconds: 3600
