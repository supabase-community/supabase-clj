# Changelog

## [0.5.0](https://github.com/supabase-community/supabase-clj/compare/storage-v0.4.0...storage-v0.5.0) (2026-08-31)


### Features

* **storage:** purge-cache, list-buckets options, cache-nonce ([d1ca5c5](https://github.com/supabase-community/supabase-clj/commit/d1ca5c53ead3caf447f2e83952fbdfac7ebe1f04))

## [0.4.0](https://github.com/supabase-community/supabase-clj/compare/storage-v0.3.0...storage-v0.4.0) (2026-07-28)


### Features

* **storage:** analytics buckets ([#36](https://github.com/supabase-community/supabase-clj/issues/36)) ([81c0d98](https://github.com/supabase-community/supabase-clj/commit/81c0d9884ccef57779f4e28bb07d8adafb0768a6))

## [0.3.0](https://github.com/supabase-community/supabase-clj/compare/storage-v0.2.0...storage-v0.3.0) (2026-07-28)


### Features

* **storage:** vector buckets ([#32](https://github.com/supabase-community/supabase-clj/issues/32)) ([bf348dc](https://github.com/supabase-community/supabase-clj/commit/bf348dcc695994f7327b43d9015d2ce661658ff0))

## [0.2.0](https://github.com/supabase-community/supabase-clj/compare/storage-v0.1.0...storage-v0.2.0) (2026-06-20)


### Features

* **storage:** metadata, update, signed uploads, transforms, streaming, list-v2 ([#11](https://github.com/supabase-community/supabase-clj/issues/11)) ([c2639ed](https://github.com/supabase-community/supabase-clj/commit/c2639ed9701c28549530a0be77c9d2547680b72e))

## [0.1.0](https://github.com/supabase-community/supabase-clj/compare/storage-v0.0.0...storage-v0.1.0) (2026-05-05)

### Features

- bucket CRUD: `list-buckets`, `get-bucket`, `create-bucket`, `update-bucket`, `empty-bucket`, `delete-bucket`
- storage instance via `from` for per-bucket file operations
- file ops: `list-files`, `remove`, `move`, `copy`, `info`, `exists?`, `upload`, `download`
- URLs: `get-public-url`, `create-signed-url`, `create-signed-urls`
