(ns crux.kafka
  "Currently uses nippy to play nice with RDF IRIs that are not
  valid keywords. Uses one transaction per message."
  (:require [crux.db :as db]
            [crux.index :as idx]
            [crux.tx :as tx]
            [clojure.tools.logging :as log])
  (:import [crux.kafka.nippy NippyDeserializer NippySerializer]
           java.io.Closeable
           java.time.Duration
           [java.util Date List Map]
           java.util.concurrent.ExecutionException
           [org.apache.kafka.clients.admin AdminClient NewTopic]
           [org.apache.kafka.clients.consumer ConsumerRebalanceListener ConsumerRecord KafkaConsumer]
           [org.apache.kafka.clients.producer KafkaProducer ProducerRecord RecordMetadata]
           org.apache.kafka.common.errors.TopicExistsException
           org.apache.kafka.common.TopicPartition))

(def default-producer-config
  {"enable.idempotence" "true"
   "acks" "all"
   "key.serializer" (.getName NippySerializer)
   "value.serializer" (.getName NippySerializer)})

(def default-consumer-config
  {"enable.auto.commit" "false"
   "isolation.level" "read_committed"
   "auto.offset.reset" "earliest"
   "key.deserializer" (.getName NippyDeserializer)
   "value.deserializer" (.getName NippyDeserializer)})

(def default-topic-config
  {"message.timestamp.type" "LogAppendTime"})

(def tx-topic-config
  {"retention.ms" (str Long/MAX_VALUE)})

(def doc-topic-config
  {"cleanup.policy" "compact"})

(defn ^KafkaProducer create-producer [config]
  (KafkaProducer. ^Map (merge default-producer-config config)))

(defn ^KafkaConsumer create-consumer [config]
  (KafkaConsumer. ^Map (merge default-consumer-config config)))

(defn ^AdminClient create-admin-client [config]
  (AdminClient/create ^Map config))

(defn create-topic [^AdminClient admin-client topic num-partitions replication-factor config]
  (let [new-topic (doto (NewTopic. topic num-partitions replication-factor)
                    (.configs (merge default-topic-config config)))]
    (try
      @(.all (.createTopics admin-client [new-topic]))
      (catch ExecutionException e
        (let [cause (.getCause e)]
          (when-not (instance? TopicExistsException cause)
            (throw e)))))))

;;; Transacting Producer

(defrecord KafkaTxLog [^KafkaProducer producer tx-topic doc-topic]
  Closeable
  (close [_])

  db/TxLog
  (submit-doc [this content-hash doc]
    (->> (ProducerRecord. doc-topic content-hash doc)
         (.send producer)))

  (submit-tx [this tx-ops]
    (let [conformed-tx-ops (tx/conform-tx-ops tx-ops)]
      (doseq [doc (tx/tx-ops->docs tx-ops)]
        (db/submit-doc this (str (idx/new-id doc)) doc))
      (let [tx-send-future (->> (ProducerRecord. tx-topic nil conformed-tx-ops)
                                (.send producer))]
        (delay
         (let [record-meta ^RecordMetadata @tx-send-future]
           (tx/->SubmittedTx (.offset record-meta) (Date. (.timestamp record-meta)))))))))

;;; Indexing Consumer

(defn consumer-record->value [^ConsumerRecord record]
  (.value record))

(defn- topic-partition-meta-key [^TopicPartition partition]
  (keyword "crux.kafka.topic-partition" (str partition)))

(defn store-topic-partition-offsets [indexer ^KafkaConsumer consumer partitions]
  (doseq [^TopicPartition partition partitions]
    (db/store-index-meta indexer
                         (topic-partition-meta-key partition)
                         (.position consumer partition))))

(defn seek-to-stored-offsets [indexer ^KafkaConsumer consumer partitions]
  (doseq [^TopicPartition partition partitions]
    (if-let [offset (db/read-index-meta indexer (topic-partition-meta-key partition))]
      (.seek consumer partition offset)
      (.seekToBeginning consumer [partition]))))

(defn- index-doc-record [indexer ^ConsumerRecord record]
  (let [content-hash (.key record)
        doc (consumer-record->value record)]
    (db/index-doc indexer content-hash doc)
    doc))

(defn- index-tx-record [indexer ^ConsumerRecord record]
  (let [tx-time (Date. (.timestamp record))
        tx-ops (consumer-record->value record)
        tx-id (.offset record)]
    (db/index-tx indexer tx-ops tx-time tx-id)
    tx-ops))

(defn consume-and-index-entities
  [{:keys [indexer ^KafkaConsumer consumer
           follower timeout tx-topic doc-topic]
    :or   {timeout 10000}}]
  (let [records (.poll consumer (Duration/ofMillis timeout))
        {:keys [last-tx-log-time] :as result}
        (reduce
          (fn [state ^ConsumerRecord record]
            (condp = (.topic record)
              tx-topic
              (do (index-tx-record indexer record)
                  (-> state
                      (update :txs inc)
                      (update :last-tx-log-time max (.timestamp record))))
              doc-topic
              (do (index-doc-record indexer record)
                  (update state :docs inc))

              (throw (ex-info "Unkown topic" {:topic (.topic record)}))))
          {:txs 0
           :docs 0
           :last-tx-log-time 0}
          records)]
    (store-topic-partition-offsets indexer consumer (.partitions records))
    (when (pos? last-tx-log-time)
      (db/store-index-meta indexer :crux.tx-log/tx-time (Date. (long last-tx-log-time))))
    (dissoc result :last-tx-log-time)))

(defn subscribe-from-stored-offsets
  [indexer ^KafkaConsumer consumer ^List topics]
  (.subscribe consumer
              topics
              (reify ConsumerRebalanceListener
                (onPartitionsRevoked [_ partitions]
                  (store-topic-partition-offsets indexer consumer partitions))
                (onPartitionsAssigned [_ partitions]
                  (seek-to-stored-offsets indexer consumer partitions)))))

;; TODO: revisit this, comes from the follower work, used to live in
;; crux.bootstrap I think.

(defrecord IndexingConsumer [running? ^Thread worker-thread options]
  Closeable
  (close [_]
    (reset! running? false)
    (.join worker-thread)))

(defmethod clojure.pprint/simple-dispatch IndexingConsumer [o]
  ((get-method clojure.pprint/simple-dispatch clojure.lang.IPersistentMap) o))

(defn- indexing-consumer-thread-main-loop
  [{:keys [running? indexer consumer options]}]
  (subscribe-from-stored-offsets
   indexer consumer [(:tx-topic options) (:doc-topic options)])
  (while @running?
    (try
      (consume-and-index-entities
       {:indexer indexer
        :consumer consumer
        :timeout 100
        :tx-topic (:tx-topic options)
        :doc-topic (:doc-topic options)})
      (catch Exception e
        (log/error e "Error while consuming and indexing from Kafka:")
        (Thread/sleep 500)))))

(defn ^Closeable create-indexing-consumer
  [admin-client consumer indexer
   {:keys [tx-topic
           replication-factor
           doc-partitions
           doc-topic
           create-topics] :as options}]
  (when create-topics
    (create-topic admin-client tx-topic 1 replication-factor tx-topic-config)
    (create-topic admin-client doc-topic doc-partitions
                  replication-factor doc-topic-config))
  (let [indexing-consumer (map->IndexingConsumer {:running? (atom true)
                                                  :indexer indexer
                                                  :consumer consumer
                                                  :options options})]
    (assoc
     indexing-consumer
     :worker-thread
     (doto (Thread. ^Runnable (partial indexing-consumer-thread-main-loop indexing-consumer)
                    "crux.kafka.indexing-consumer-thread")
       (.start)))))
