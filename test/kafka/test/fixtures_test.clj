(ns kafka.test.fixtures-test
  (:require
   [kafka.admin :as admin]
   [kafka.core :as kafka]
   [kafka.zk :as zk]
   [kafka.test.config :as config]
   [kafka.test.fs :as fs]
   [kafka.test.fixtures :as fix]
   [clojure.test :refer :all]))

(deftest zookeeper-test
  (let [fix (fix/zookeeper config/broker)
        t (fn []
            (let [client (zk/client config/broker)]
              (is client)
              (.close client)))]
    (testing "zookeeper up/down"
      (fix t))))

(deftest broker-test
  (let [fix (compose-fixtures
             (fix/zookeeper config/broker)
             (fix/broker config/broker))
        t (fn []
            (let [client (zk/client config/broker)
                  utils (zk/utils client)]
              (is (.pathExists utils "/brokers/ids/0"))))]
    (testing "broker up/down"
      (fix t))))

(deftest producer-test
  (let [fix (join-fixtures
             [(fix/zookeeper config/broker)
              (fix/broker config/broker)
              (fix/producer-registry {:words config/producer})])
        t (fn []
            (let [[a aa] [@(fix/publish! :words {:topic "words"
                                                 :key "1"
                                                 :value "a"})
                          @(fix/publish! :words {:topic "words"
                                                 :key "2"
                                                 :value "aa"})]]
              (is (= 1 (:serializedValueSize (kafka/metadata a))))
              (is (= 2 (:serializedValueSize (kafka/metadata aa))))))]
    (testing "producer publish!"
      (fix t))))

(defn call-with-consumer-queue
  "Functionally consume a consumer"
  [f consumer]
  (let [latch (fix/latch 1)
        queue (fix/queue 10)
        proc (fix/consumer-loop consumer queue latch)]
    (try
      (f queue)
      (finally
        (.countDown latch)
        @proc))))

(deftest consumer-test
  (let [fix (join-fixtures
             [(fix/zookeeper config/broker)
              (fix/broker config/broker)
              (fix/consumer-registry {:words config/consumer})
              (fix/producer-registry {:words config/producer})])
        t #(call-with-consumer-queue
            (fn [queue]
              @(fix/publish! :words {:topic "words"
                                     :key "1"
                                     :value "a"})

              @(fix/publish! :words {:topic "words"
                                     :key "2"
                                     :value "aa"})

              (let [[a aa] [(.take queue)
                            (.take queue)]]
                (is (= {:topic "words"
                        :key "1"
                        :value "a"}
                       (kafka/select-methods a [:topic :key :value])))
                (is (= {:topic "words"
                        :key "2"
                        :value "aa"}
                       (kafka/select-methods aa [:topic :key :value])))))
            (fix/find-consumer :words))]
    (fix t)))
