# Changelog

## [0.2.0](https://github.com/supabase-community/supabase-clj/compare/storage-v0.1.0...storage-v0.2.0) (2026-06-20)


### Features

* **storage:** metadata, update, signed uploads, transforms, streaming, list-v2 ([#11](https://github.com/supabase-community/supabase-clj/issues/11)) ([c2639ed](https://github.com/supabase-community/supabase-clj/commit/c2639ed9701c28549530a0be77c9d2547680b72e))

## [0.1.0](https://github.com/supabase-community/supabase-clj/compare/storage-v0.0.0...storage-v0.1.0) (2026-05-05)

### Features

- bucket CRUD: `list-buckets`, `get-bucket`, `create-bucket`, `update-bucket`, `empty-bucket`, `delete-bucket`
- storage instance via `from` for per-bucket file operations
- file ops: `list-files`, `remove`, `move`, `copy`, `info`, `exists?`, `upload`, `download`
- URLs: `get-public-url`, `create-signed-url`, `create-signed-urls`
