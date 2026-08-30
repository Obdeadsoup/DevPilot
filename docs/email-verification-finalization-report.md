# Email Verification Finalization Report

## Baseline and existing design

Branch is `agent`, HEAD is `5ce0b4abedf89f845a49a6f862f06e1defd378f4`; prior uncommitted work was preserved.
Identity already owned `dp_user`, BCrypt and Redis-backed opaque Bearer Tokens. This change adds no user table,
JWT, or second identity system.

## Architecture and Redis design

`POST /api/v1/auth/verification/email` is anonymous alongside precise register/login routes. `VerificationCodeService`
normalizes email, checks existing users, creates a six-digit SecureRandom code, and uses Redis Lua to reserve:

- `devpilot:auth:verification:email:code:{email}` — code, 5 minutes.
- `...:cooldown:{email}` — send cooldown, 60 seconds.
- `...:ip:{hash}` — basic IP cooldown, 60 seconds.
- `...:failures:{email}` — incorrect-code counter, five-minute TTL and maximum five attempts.

The service depends only on `VerificationCodeSender`. `SmtpEmailVerificationCodeSender` is active for `smtp`/`prod`
and delegates to JavaMailSender; local/dev/test uses the non-network Logging Sender and never logs a real code.
SMTP failures conditionally remove only the matching reserved code/cooldown. Registration prechecks database uniqueness,
atomically verifies and consumes code, then uses its existing short MySQL transaction to insert the BCrypt user. The
consume-before-DB boundary intentionally favors at-most-once code use; a rare concurrent DB uniqueness conflict requires
a fresh code rather than making a consumed code reusable.

## Call chains

```text
RegisterView → Gateway → AuthController → VerificationCodeService
→ Redis Lua + VerificationCodeSender → SMTP Adapter → JavaMailSender → QQ/163 SMTP → inbox

RegisterView → Gateway → AuthController → RegistrationService
→ VerificationCodeService.verifyAndConsume → PasswordEncoder → UserMapper → MySQL
```

## Tests and manual SMTP verification

`mvn -pl devpilot-identity -am test` passed (18 tests), including generated leading-zero code, cooldown, wrong/locked
code and sender-failure behavior. `npm run build` passed. Configure `.env` with a QQ/163 SMTP app password, start Core
with `--spring.profiles.active=local,smtp`, open `/register`, request a code to a real mailbox, submit it, then log in
and call `/me`.

REAL_SMTP_MANUAL_VERIFICATION_REQUIRED: no real mail credentials are available to this environment, so no SMTP delivery
is claimed. Docker also remains unavailable for Testcontainers/full-stack smoke.

## Updated file map and reading order

New: `VerificationCodeSender`, `VerificationCodeService`, properties, SMTP/Logging adapters, email request DTO and
service tests. Modified: identity POM, error codes, registration DTO/service/controller, security whitelist, Core config,
environment template, RegisterView/auth API/types, README and gitignore. Read: Port → VerificationCodeService → SMTP
Adapter → RegistrationService → AuthController → SecurityConfiguration → RegisterView → auth API.
