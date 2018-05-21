(ns crux.doc
  (:require [crux.byte-utils :as bu]
            [crux.kv-store :as ks]
            [crux.db]
            [taoensso.nippy :as nippy])
  (:import [java.nio ByteBuffer]
           [java.util Date]))

(set! *unchecked-math* :warn-on-boxed)

(def ^:const content-hash->doc-index-id 0)
(def ^:const attribute+value+content-hash-index-id 1)

(def ^:const content-hash+entity-index-id 2)
(def ^:const entity+business-time+transact-time+tx-id->content-hash-index-id 3)

(def ^:const meta-key->value-index-id 4)

(def empty-byte-array (byte-array 0))
(def ^:const sha1-size 20)

(defn encode-doc-key ^bytes [^bytes content-hash]
  (-> (ByteBuffer/allocate (+ Short/BYTES sha1-size))
      (.putShort content-hash->doc-index-id)
      (.put content-hash)
      (.array)))

(defn decode-doc-key ^bytes [^bytes doc-key]
  (let [buffer (ByteBuffer/wrap doc-key)]
    (assert (= content-hash->doc-index-id (.getShort buffer)))
    (doto (byte-array sha1-size)
      (->> (.get buffer)))))

(defn encode-attribute+value+content-hash-key ^bytes [k v ^bytes content-hash]
  (-> (ByteBuffer/allocate (+ Short/BYTES sha1-size sha1-size (alength content-hash)))
      (.putShort attribute+value+content-hash-index-id)
      (.put (bu/sha1 (nippy/freeze k)))
      (.put (bu/sha1 (nippy/freeze v)))
      (.put content-hash)
      (.array)))

(defn encode-attribute+value-prefix-key ^bytes [k v]
  (encode-attribute+value+content-hash-key k v empty-byte-array))

(defn decode-attribute+value+content-hash-key->content-hash ^bytes [^bytes key]
  (let [buffer (ByteBuffer/wrap key)]
    (assert (= attribute+value+content-hash-index-id (.getShort buffer)))
    (.position buffer (+ Short/BYTES sha1-size sha1-size))
    (doto (byte-array sha1-size)
      (->> (.get buffer)))))

(defn encode-content-hash+entity-key ^bytes [^bytes content-hash ^bytes eid]
  (-> (ByteBuffer/allocate (+ Short/BYTES sha1-size (alength eid)))
      (.putShort content-hash+entity-index-id)
      (.put content-hash)
      (.put eid)
      (.array)))

(defn encode-content-hash-prefix-key ^bytes [^bytes content-hash]
  (encode-content-hash+entity-key content-hash empty-byte-array))

(defn decode-content-hash+entity-key->entity ^bytes [^bytes key]
    (let [buffer (ByteBuffer/wrap key)]
      (assert (= content-hash+entity-index-id (.getShort buffer)))
      (.position buffer (+ Short/BYTES sha1-size))
      (doto (byte-array sha1-size)
        (->> (.get buffer)))))

(defn encode-meta-key ^bytes [k]
  (let [k ^bytes (nippy/freeze k)]
    (-> (ByteBuffer/allocate (+ Short/BYTES (alength k)))
        (.putShort meta-key->value-index-id)
        (.put k)
        (.array))))

(def ^:const max-timestamp (.getTime #inst "9999-12-30"))

(defn date->reverse-time-ms ^long [^Date date]
  (- max-timestamp (.getTime date)))

(defn ^Date reverse-time-ms->date [^long reverse-time-ms]
  (Date. (- max-timestamp reverse-time-ms)))

(defn encode-entity+business-time+transact-time+tx-id-key ^bytes [^bytes eid ^Date business-time ^Date transact-time ^long tx-id]
  (-> (ByteBuffer/allocate (+ Short/BYTES sha1-size Long/BYTES Long/BYTES Long/BYTES))
      (.putShort entity+business-time+transact-time+tx-id->content-hash-index-id)
      (.put eid)
      (.putLong (date->reverse-time-ms business-time))
      (.putLong (date->reverse-time-ms transact-time))
      (.putLong tx-id)
      (.array)))

(defn encode-entity+business-time+transact-time-prefix-key ^bytes [^bytes eid ^Date business-time ^Date transact-time]
  (encode-entity+business-time+transact-time+tx-id-key eid business-time transact-time 0))

(defn decode-entity+business-time+transact-time+tx-id-key ^bytes [^bytes key]
    (let [buffer (ByteBuffer/wrap key)]
      (assert (= entity+business-time+transact-time+tx-id->content-hash-index-id (.getShort buffer)))
      (.position buffer (+ Short/BYTES sha1-size))
      {:business-time (reverse-time-ms->date (.getLong buffer))
       :transact-time (reverse-time-ms->date (.getLong buffer))
       :tx-id (.getLong buffer)}))

(defn key->bytes [k]
  (cond-> k
    (string? k) bu/hex->bytes))

;; Docs

(defn all-doc-keys [kv]
  (let [seek-k (.array (.putShort (ByteBuffer/allocate Short/BYTES) content-hash->doc-index-id))]
    (ks/iterate-with
     kv
     (fn [i]
       (loop [[k v :as kv] (ks/-seek i seek-k)
              acc #{}]
         (if (and kv (bu/bytes=? seek-k k))
           (let [content-hash (decode-doc-key k)]
             (recur (ks/-next i) (conj acc (bu/bytes->hex content-hash))))
           acc))))))

(defn doc-entries [kv ks]
  (ks/iterate-with
   kv
   (fn [i]
     (set (for [seek-k (->> (map (comp encode-doc-key key->bytes) ks)
                            (into (sorted-set-by bu/bytes-comparator)))
                :let [[k v :as kv] (ks/-seek i seek-k)]
                :when (and k (bu/bytes=? seek-k k))]
            kv)))))

(defn docs [kv ks]
  (->> (for [[k v] (doc-entries kv ks)]
         [(bu/bytes->hex (decode-doc-key k))
          (nippy/thaw v)])
       (into {})))

(defn existing-doc-keys [kv ks]
  (->> (for [[k v] (doc-entries kv ks)]
         (bu/bytes->hex (decode-doc-key k)))
       (into #{})))

(defn doc->content-hash [doc]
  (bu/sha1 (nippy/freeze doc)))

(defn store-docs [kv docs]
  (let [content-hash->doc+bytes (->> (for [doc docs
                                           :let [bs (nippy/freeze doc)
                                                 k (bu/sha1 bs)]]
                                       [k [doc bs]])
                                     (into (sorted-map-by bu/bytes-comparator)))
        existing-keys (existing-doc-keys kv (keys content-hash->doc+bytes))
        content-hash->new-docs+bytes (apply dissoc content-hash->doc+bytes existing-keys)]
    (ks/store kv (concat
                  (for [[content-hash [doc bs]] content-hash->new-docs+bytes]
                    [(encode-doc-key content-hash)
                     bs])
                  (for [[content-hash [doc]] content-hash->new-docs+bytes
                        [k v] doc
                        v (if (or (vector? v)
                                  (set? v))
                            v
                            [v])]
                    [(encode-attribute+value+content-hash-key k v content-hash)
                     empty-byte-array])))
    (mapv bu/bytes->hex (keys content-hash->new-docs+bytes))))

(defn find-doc-keys-by-attribute-values [kv k vs]
  (ks/iterate-with
   kv
   (fn [i]
     (->> (for [seek-k (->> (for [v vs]
                              (encode-attribute+value-prefix-key k v))
                            (into (sorted-set-by bu/bytes-comparator)))]
            (loop [[k v :as kv] (ks/-seek i seek-k)
                   acc []]
              (if (and kv (bu/bytes=? seek-k k))
                (let [content-hash (decode-attribute+value+content-hash-key->content-hash k)]
                  (recur (ks/-next i) (conj acc (bu/bytes->hex content-hash))))
                acc)))
          (reduce into #{})))))

;; Txs

(defn entity->eid-bytes [k]
  (if (bytes? k)
    k
    (bu/sha1 (nippy/freeze k))))

(defn entities-by-content-hashes [kv content-hashes]
  (ks/iterate-with
   kv
   (fn [i]
     (->> (for [[content-hash seek-k] (->> (for [content-hash content-hashes]
                                             [content-hash (encode-content-hash-prefix-key (bu/hex->bytes content-hash))])
                                           (into (sorted-map-by bu/bytes-comparator)))
                :let [[k v :as kv] (ks/-seek i seek-k)]
                :when (and k (bu/bytes=? seek-k k))]
            {content-hash
             [(bu/bytes->hex (decode-content-hash+entity-key->entity k))]})
          (apply merge-with concat {})))))

(defn entities-at [kv entities business-time transact-time]
  (ks/iterate-with
   kv
   (fn [i]
     (let [prefix-size (+ Short/BYTES sha1-size)]
       (->> (for [[entity seek-k] (->> (for [entity entities]
                                         [entity (encode-entity+business-time+transact-time-prefix-key
                                                  (entity->eid-bytes entity)
                                                  business-time
                                                  transact-time)])
                                       (into (sorted-map-by bu/bytes-comparator)))
                  :let [[k v :as kv] (ks/-seek i seek-k)]
                  :when (and k
                             (bu/bytes=? seek-k prefix-size k)
                             (<= (bu/compare-bytes seek-k k) 0)
                             (pos? (alength ^bytes v)))]
              [entity (-> (decode-entity+business-time+transact-time+tx-id-key k)
                          (assoc :content-hash (bu/bytes->hex v)))])
            (into {}))))))

(defn find-entities-by-attribute-values-at [kv k vs business-time transact-time]
  (->> (for [[content-hash entities] (->> (find-doc-keys-by-attribute-values kv k vs)
                                          (entities-by-content-hashes))
             [entity entity-map] (entities-at kv entities business-time transact-time)
             :when (= content-hash (:content-hash entity-map))]
         [entity entity-map])
       (into {})))

(defn store-txs [kv ops transact-time tx-id]
  (->> (for [[op k v business-time :as operation] ops
             :let [eid (entity->eid-bytes k)
                   content-hash (bu/hex->bytes v)]]
         (case op
           :crux.tx/put
           [[(encode-entity+business-time+transact-time+tx-id-key
              eid
              (or business-time transact-time)
              transact-time
              tx-id)
             content-hash]
            [(encode-content-hash+entity-key content-hash eid)
             empty-byte-array]]

           :crux.tx/delete
           [[(encode-entity+business-time+transact-time+tx-id-key
              eid
              (or business-time transact-time)
              transact-time
              tx-id)
             empty-byte-array]]

           :crux.tx/cas
           (let [[op k old-v new-v business-time] operation
                 old-content-hash (-> (entities-at kv [k] business-time transact-time)
                                      (get k)
                                      :content-hash)]
             (when (= old-content-hash old-v)
               [[(encode-entity+business-time+transact-time+tx-id-key
                  eid
                  (or business-time transact-time)
                  transact-time
                  tx-id)
                 new-v]
                [(encode-content-hash+entity-key new-v eid)
                 empty-byte-array]]))))
       (reduce into {})
       (ks/store kv)))

;; Query

;; NOTE: this is a simple, non-temporal store using content hashes as ids.
(defrecord DocDatasource [kv]
  crux.db/Datasource
  (entities [this]
    (all-doc-keys kv))

  (entities-for-attribute-value [this ident v]
    (find-doc-keys-by-attribute-values kv ident [v]))

  (attr-val [this eid ident]
    (get-in (docs kv [eid]) [eid ident])))
