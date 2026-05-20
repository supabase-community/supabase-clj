(ns supabase.realtime.protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [supabase.realtime.protocol :as proto]))

(deftest realtime-topic-test
  (testing "prefixes when absent"
    (is (= "realtime:room:1" (proto/realtime-topic "room:1"))))
  (testing "idempotent"
    (is (= "realtime:x" (proto/realtime-topic "realtime:x")))))

(deftest ref-counter-monotonic
  (let [c (proto/ref-counter)]
    (is (= "1" (proto/next-ref c)))
    (is (= "2" (proto/next-ref c)))
    (is (= "3" (proto/next-ref c)))))

(deftest encode-decode-round-trip
  (let [frame {:topic "realtime:t" :event "phx_join" :payload {:a 1} :ref "1"}
        text (proto/encode frame)
        decoded (proto/parse-frame text)]
    (is (= "realtime:t" (:topic decoded)))
    (is (= "phx_join" (:event decoded)))
    (is (= {:a 1} (:payload decoded)))
    (is (= "1" (:ref decoded)))))

(deftest join-frame-postgres-bindings
  (let [bindings [{:type :postgres-changes
                   :filter {:event :insert :schema "public" :table "users"}}
                  {:type :broadcast :filter {:event "x"}}
                  {:type :postgres-changes
                   :filter {:event :update :schema "public" :table "posts"
                            :filter "id=eq.1"}}]
        f (proto/join-frame "1" "realtime:r" {:broadcast {:self false}} bindings "tok")]
    (is (= "phx_join" (:event f)))
    (is (= "1" (:ref f)))
    (is (= "1" (:join_ref f)))
    (is (= "tok" (get-in f [:payload :access_token])))
    (let [pg (get-in f [:payload :config :postgres_changes])]
      (is (= 2 (count pg)))
      (is (= "insert" (:event (first pg))))
      (is (= "public" (:schema (first pg))))
      (is (= "users" (:table (first pg))))
      (is (not (contains? (first pg) :filter)))
      (is (= "id=eq.1" (:filter (second pg)))))))

(deftest join-frame-no-token-omits-key
  (let [f (proto/join-frame "1" "t" {} [] nil)]
    (is (not (contains? (:payload f) :access_token)))))

(deftest leave-frame-basic
  (let [f (proto/leave-frame "5" "1" "realtime:r")]
    (is (= "phx_leave" (:event f)))
    (is (= "1" (:join_ref f)))
    (is (= "5" (:ref f)))))

(deftest heartbeat-frame-topic
  (let [f (proto/heartbeat-frame "9")]
    (is (= "phoenix" (:topic f)))
    (is (= "heartbeat" (:event f)))
    (is (= {} (:payload f)))))

(deftest broadcast-frame-shape
  (let [f (proto/broadcast-frame "3" "1" "realtime:r" "typing" {:user "a"})]
    (is (= "broadcast" (:event f)))
    (is (= "broadcast" (get-in f [:payload :type])))
    (is (= "typing" (get-in f [:payload :event])))
    (is (= {:user "a"} (get-in f [:payload :payload])))))

(deftest presence-frames
  (let [t (proto/presence-track-frame "1" "j" "r" {:k "v"})
        u (proto/presence-untrack-frame "2" "j" "r")]
    (is (= "presence" (:event t)))
    (is (= "track" (get-in t [:payload :event])))
    (is (= {:k "v"} (get-in t [:payload :payload])))
    (is (= "untrack" (get-in u [:payload :event])))))

(deftest access-token-frame-shape
  (let [f (proto/access-token-frame "1" "j" "r" "newtok")]
    (is (= "access_token" (:event f)))
    (is (= "newtok" (get-in f [:payload :access_token])))))

(deftest event-kind-dispatch
  (is (= :heartbeat-reply
         (proto/event-kind {:topic "phoenix" :event "phx_reply"})))
  (is (= :phx-reply
         (proto/event-kind {:topic "realtime:t" :event "phx_reply"})))
  (is (= :postgres-changes (proto/event-kind {:event "postgres_changes"})))
  (is (= :broadcast        (proto/event-kind {:event "broadcast"})))
  (is (= :presence-state   (proto/event-kind {:event "presence_state"})))
  (is (= :presence-diff    (proto/event-kind {:event "presence_diff"})))
  (is (= :phx-error        (proto/event-kind {:event "phx_error"})))
  (is (= :phx-close        (proto/event-kind {:event "phx_close"})))
  (is (= :system           (proto/event-kind {:event "system"})))
  (is (= :unknown          (proto/event-kind {:event "weird"}))))
