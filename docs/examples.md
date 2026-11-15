# Examples

## attendance

A JWT login demo backed by SQLite. Set `ADMIN_USER` and `ADMIN_PASSWORD` (at least 12 characters) on first start to seed the only user; without them the app starts with no users and logs how to seed one. Passwords are stored as PBKDF2WithHmacSHA256 hashes with a random salt per user. Login throttling is not implemented, so the example is not production-ready.
