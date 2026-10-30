# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | ✅        |

## Reporting a Vulnerability

If you discover a security vulnerability, please **do not open a public issue**.

Instead, use [GitHub's private vulnerability reporting](https://github.com/Mein-Augenstern/star-site-components/security/advisories/new) or email the maintainer directly.

We aim to respond within 48 hours and provide a fix within 7 days for critical issues.

## Security Considerations

- **Admin endpoints**: async-command's admin controller is disabled by default (`admin-enabled=false`). When enabled, configure `admin-token` or provide a custom `AsyncCommandAdminGuard` bean.
- **DDL auto-execution**: all components can auto-create tables (`init-ddl=true`). Disable in production if your DBA manages schema.
- **SQL injection prevention**: table names are whitelist-validated (`[A-Za-z0-9_]+`); all values use parameter binding.
