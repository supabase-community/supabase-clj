# Changelog

## [0.7.0](https://github.com/supabase-community/supabase-clj/compare/core-v0.6.1...core-v0.7.0) (2026-08-18)


### Features

* **core:** retry policy, telemetry seam, structured x-client-info ([0aec21c](https://github.com/supabase-community/supabase-clj/commit/0aec21c3299828e90e7915d1ba37b330ab79e432))

## [0.6.1](https://github.com/supabase-community/supabase-clj/compare/core-v0.6.0...core-v0.6.1) (2026-07-28)


### Bug Fixes

* **core:** tag ensure-client anomaly with :core service ([#38](https://github.com/supabase-community/supabase-clj/issues/38)) ([5ea7f13](https://github.com/supabase-community/supabase-clj/commit/5ea7f13856db58005467beb868b22c065462deb9))

## [0.6.0](https://github.com/supabase-community/supabase-clj/compare/core-v0.5.0...core-v0.6.0) (2026-06-20)


### Features

* **core:** make execute-async future cancel the in-flight request ([9b54fb2](https://github.com/supabase-community/supabase-clj/commit/9b54fb2816ee0e2ac391fcd49a598395a412bf78))

## [0.5.0](https://github.com/supabase-community/supabase-clj/compare/core-v0.4.0...core-v0.5.0) (2026-05-27)


### Features

* **core:** pluggable transport, multipart, streaming, pool, logging ([2c2cbe3](https://github.com/supabase-community/supabase-clj/commit/2c2cbe3cc45a77040c8c0e800db534118fed2852))

## [0.4.0](https://github.com/supabase-community/supabase-clj/compare/core-v0.3.0...core-v0.4.0) (2026-05-20)


### Features

* **core:** add merge-query-param helper for stacked query values ([af0f884](https://github.com/supabase-community/supabase-clj/commit/af0f884b6c37c209aefb7f8447d8c2abcdf11bc5))

## [0.3.0](https://github.com/supabase-community/supabase-clj/compare/core-v0.2.0...core-v0.3.0) (2026-05-05)

### Features

- support binary response body on `http/execute` via `with-response-as` ([2389a29](https://github.com/supabase-community/supabase-clj/commit/2389a29))

## [0.2.0](https://github.com/supabase-community/supabase-clj/compare/root-docs-v0.1.0...root-docs-v0.2.0) (2026-04-27)

### Features

- introduce `client/ensure-client` validator

## [0.1.0](https://github.com/supabase-community/supabase-clj/compare/core-v0.1.0...core-v0.1.0) (2026-04-06)

### Features

- client main namespace and tests ([c2ff7dd](https://github.com/supabase-community/supabase-clj/commit/c2ff7dd25d4590da3737753fc703b6dae5c46b98))
- composable http namespace ([b6133d7](https://github.com/supabase-community/supabase-clj/commit/b6133d796cceb0677a93af14e5c01f1e4b41be88))
- core module setup and error anomalies namespace ([ff5acec](https://github.com/supabase-community/supabase-clj/commit/ff5acecb414775f50d790ed040ba613128c810f1))

### Continuous Integration

- formatting and fixing minor issues ([b97dc17](https://github.com/supabase-community/supabase-clj/commit/b97dc175dfeac0985aebcc0e77092824bc5da9ed))
