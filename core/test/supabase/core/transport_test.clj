(ns supabase.core.transport-test
  (:require [clojure.test :refer [deftest is testing]]
            [supabase.core.transport :as transport])
  (:import (java.net.http HttpClient HttpClient$Redirect HttpClient$Version)
           (java.util.concurrent CompletableFuture)))

(defrecord StubTransport [response]
  transport/Transport
  (execute [_ _req] response)
  (execute-async [_ _req] (CompletableFuture/completedFuture response)))

(deftest transport-protocol-test
  (testing "any record satisfying Transport works"
    (let [t (->StubTransport {:status 200 :body "ok" :headers {}})]
      (is (= 200 (:status (transport/execute t {}))))
      (is (= 200 (:status @(transport/execute-async t {})))))))

(deftest hato-transport-test
  (testing "default constructor creates transport without explicit HttpClient"
    (let [t (transport/hato-transport)]
      (is (satisfies? transport/Transport t))
      (is (nil? (:http-client t)))))

  (testing "pool opts produce a configured HttpClient"
    (let [t (transport/hato-transport {:connect-timeout 1000 :version :http-1.1})]
      (is (instance? HttpClient (:http-client t)))))

  (testing "empty pool opts skip building a custom HttpClient"
    (let [t (transport/hato-transport {})]
      (is (nil? (:http-client t))))))

(deftest build-http-client-test
  (testing "honors version and redirect-policy"
    (let [client (transport/build-http-client
                   {:connect-timeout 500 :version :http-2 :redirect-policy :never})]
      (is (instance? HttpClient client))
      (is (= HttpClient$Version/HTTP_2 (.version client)))
      (is (= HttpClient$Redirect/NEVER (.followRedirects client))))))

(deftest resolve-transport-test
  (testing "request transport wins"
    (let [t1 (->StubTransport :req)
          t2 (->StubTransport :client)
          req {:transport t1 :client {:transport t2}}]
      (is (= t1 (transport/resolve-transport req)))))

  (testing "client transport used when no request transport"
    (let [t (->StubTransport :client)
          req {:client {:transport t}}]
      (is (= t (transport/resolve-transport req)))))

  (testing "default transport when neither set"
    (is (= transport/default-transport (transport/resolve-transport {})))))
