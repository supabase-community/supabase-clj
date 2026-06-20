# io.github.supabase-community/storage

Object storage integration for Supabase. Bucket CRUD, file upload/download,
public + signed URLs, list/move/copy/remove operations.

## Installation

```clojure
;; deps.edn
{:deps
 {io.github.supabase-community/core {:mvn/version "0.3.0"}
  io.github.supabase-community/storage {:mvn/version "0.2.0"}}} ;; x-release-please-version
```

## Quick Start

```clojure
(require '[supabase.core.client :as client]
         '[supabase.storage :as storage])

(def c (client/make-client "https://abc.supabase.co" "anon-key"))

;; Bucket CRUD
(storage/list-buckets c)
(storage/create-bucket c "avatars" {:public true})
(storage/get-bucket c "avatars")
(storage/update-bucket c "avatars" {:public false})
(storage/empty-bucket c "avatars")
(storage/delete-bucket c "avatars")

;; Per-bucket file operations via a storage instance
(def s (storage/from c "avatars"))

(storage/upload s "profile.png" my-bytes
                {:content-type "image/png" :upsert true})
(storage/download s "profile.png")  ;; => byte array body
(storage/list-files s "folder/" {:limit 50})
(storage/exists? s "profile.png")
(storage/info s "profile.png")
(storage/move s {:from "a.png" :to "b.png"})
(storage/copy s {:from "a.png" :to "b.png"})
(storage/remove s ["a.png" "b.png"])

;; URLs
(storage/get-public-url s "profile.png")
(storage/create-signed-url s "profile.png" {:expires-in 60})
(storage/create-signed-urls s ["a.png" "b.png"] {:expires-in 60})
```

## License

MIT
