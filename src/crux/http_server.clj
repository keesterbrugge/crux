(ns crux.http-server
  "HTTP API for Crux.

  Requires ring/ring-core, ring/ring-jetty-adapter and
  org.apache.kafka/kafka-clients on the classpath.

  The optional SPARQL handler requires further dependencies on the
  classpath, see crux.sparql.protocol for details."
  (:require [clojure.edn :as edn]
            [clojure.instant :as instant]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [crux.codec :as c]
            [crux.io :as cio]
            [crux.bootstrap :as b]
            [crux.kafka]
            [crux.tx :as tx]
            [ring.adapter.jetty :as j]
            [ring.middleware.params :as p]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.util.request :as req]
            [ring.util.io :as rio]
            [ring.util.time :as rt])
  (:import [java.io Closeable IOException]
           java.time.Duration
           java.util.Date
           java.util.UUID
           org.eclipse.jetty.server.Server
           [crux.api ICruxDatasource ICruxAPI]))

;; ---------------------------------------------------
;; Utils

(defn- body->edn [request]
  (-> request
      req/body-string
      edn/read-string))

(defn- check-path [[path-pattern valid-methods] request]
  (let [path (req/path-info request)
        method (:request-method request)]
    (and (re-find path-pattern path)
         (some #{method} valid-methods))))

(defn- response
  ([status headers body]
   {:status status
    :headers headers
    :body body}))

(defn- success-response [m]
  (response (if m
              200
              404)
            {"Content-Type" "application/edn"}
            (pr-str m)))

(defn- exception-response [status ^Exception e]
  (response status
            {"Content-Type" "application/edn"}
            (with-out-str
              (pp/pprint (Throwable->map e)))))

(defn- wrap-exception-handling [handler]
  (fn [request]
    (try
      (try
        (handler request)
        (catch Exception e
          (if (and (.getMessage e)
                   (str/starts-with? (.getMessage e) "Spec assertion failed"))
            (exception-response 400 e) ;; Valid edn, invalid content
            (do (log/error e "Exception while handling request:" (pr-str request))
                (exception-response 500 e))))) ;; Valid content; something internal failed, or content validity is not properly checked
      (catch Exception e
        (exception-response 400 e))))) ;;Invalid edn

(defn- add-last-modified [response date]
  (if date
    (->> (rt/format-date date)
         (assoc-in response [:headers "Last-Modified"]))
    response))

;; ---------------------------------------------------
;; Services

(defn- status [^ICruxAPI crux-system]
  (let [status-map (.status crux-system)]
    (if (or (not (contains? status-map :crux.zk/zk-active?))
            (:crux.zk/zk-active? status-map)
            (not= (System/getenv "CRUX_MODE") "CLUSTER_NODE"))
      (success-response status-map)
      (response 500
                {"Content-Type" "application/edn"}
                (pr-str status-map)))))

(defn- document [^ICruxAPI crux-system request]
  (let [[_ content-hash] (re-find #"^/document/(.+)$" (req/path-info request))]
    (success-response
     (.document crux-system (c/new-id content-hash)))))

(defn- history [^ICruxAPI crux-system request]
  (let [[_ eid] (re-find #"^/history/(.+)$" (req/path-info request))
        history (.history crux-system (c/new-id eid))]
    (-> (success-response history)
        (add-last-modified (:crux.tx/tx-time (first history))))))

(defn- history-range [^ICruxAPI crux-system request]
  (let [[_ eid] (re-find #"^/history-range/(.+)$" (req/path-info request))
        valid-time-start (some->> (get-in request [:query-params "valid-time-start"])
                                  (cio/parse-rfc3339-or-millis-date))
        transaction-time-start (some->> (get-in request [:query-params "transaction-time-start"])
                                        (cio/parse-rfc3339-or-millis-date))
        valid-time-end (some->> (get-in request [:query-params "valid-time-end"])
                                (cio/parse-rfc3339-or-millis-date))
        transaction-time-end (some->> (get-in request [:query-params "transaction-time-end"])
                                      (cio/parse-rfc3339-or-millis-date))
        history (.historyRange crux-system (c/new-id eid) valid-time-start transaction-time-start valid-time-end transaction-time-end)
        last-modified (:crux.tx/tx-time (last history))]
    (-> (success-response history)
        (add-last-modified (:crux.tx/tx-time (last history))))))

(defn- db-for-request ^ICruxDatasource [^ICruxAPI crux-system {:keys [valid-time transact-time]}]
  (cond
    (and valid-time transact-time)
    (.db crux-system valid-time transact-time)

    valid-time
    (.db crux-system valid-time)

    ;; TODO: This could also be an error, depending how you see it,
    ;; not supported via the Java API itself.
    transact-time
    (.db crux-system (cio/next-monotonic-date) transact-time)

    :else
    (.db crux-system)))

(defn- streamed-edn-response [^Closeable ctx edn]
  (try
    (->> (rio/piped-input-stream
          (fn [out]
            (with-open [ctx ctx
                        out (io/writer out)]
              (.write out "(")
              (doseq [x edn]
                (.write out (pr-str x)))
              (.write out ")"))))
         (response 200 {"Content-Type" "application/edn"}))
    (catch Throwable t
      (.close ctx)
      (throw t))))

(def ^:private date? (partial instance? Date))
(s/def ::valid-time date?)
(s/def ::transact-time date?)

(s/def ::query-map (s/and #(set/superset? #{:query :valid-time :transact-time} (keys %))
                          (s/keys :req-un [:crux.query/query]
                                  :opt-un [::valid-time
                                           ::transact-time])))

;; TODO: Potentially require both valid and transaction time sent by
;; the client?
(defn- query [^ICruxAPI crux-system request]
  (let [query-map (s/assert ::query-map (body->edn request))
        db (db-for-request crux-system query-map)]
    (-> (success-response
         (.q db (:query query-map)))
        (add-last-modified (.transactionTime db)))))

(defn- query-stream [^ICruxAPI crux-system request]
  (let [query-map (s/assert ::query-map (body->edn request))
        db (db-for-request crux-system query-map)
        snapshot (.newSnapshot db)
        result (.q db snapshot (:query query-map))]
    (-> (streamed-edn-response snapshot result)
        (add-last-modified (.transactionTime db)))))

(s/def ::eid c/valid-id?)
(s/def ::entity-map (s/and #(set/superset? #{:eid :valid-time :transact-time} (keys %))
                           (s/keys :req-un [::eid]
                                   :opt-un [::valid-time
                                            ::transact-time])))

;; TODO: Could support as-of now via path and GET.
(defn- entity [^ICruxAPI crux-system request]
  (let [{:keys [eid] :as body} (s/assert ::entity-map (body->edn request))
        db (db-for-request crux-system body)
        {:keys [crux.tx/tx-time] :as entity-tx} (.entityTx db eid)]
    (-> (success-response (.entity db eid))
        (add-last-modified tx-time))))

(defn- entity-tx [^ICruxAPI crux-system request]
  (let [{:keys [eid] :as body} (s/assert ::entity-map (body->edn request))
        db (db-for-request crux-system body)
        {:keys [crux.tx/tx-time] :as entity-tx} (.entityTx db eid)]
    (-> (success-response entity-tx)
        (add-last-modified tx-time))))

(defn- history-ascending [^ICruxAPI crux-system request]
  (let [{:keys [eid] :as body} (s/assert ::entity-map (body->edn request))
        db (db-for-request crux-system body)
        snapshot (.newSnapshot db)
        history (.historyAscending db snapshot (c/new-id eid))]
    (-> (streamed-edn-response snapshot history)
        (add-last-modified (tx/latest-completed-tx-time (:crux.tx-log/consumer-state (.status crux-system)))))))

(defn- history-descending [^ICruxAPI crux-system request]
  (let [{:keys [eid] :as body} (s/assert ::entity-map (body->edn request))
        db (db-for-request crux-system body)
        snapshot (.newSnapshot db)
        history (.historyDescending db snapshot (c/new-id eid))]
    (-> (streamed-edn-response snapshot history)
        (add-last-modified (tx/latest-completed-tx-time (:crux.tx-log/consumer-state (.status crux-system)))))))

(defn- transact [^ICruxAPI crux-system request]
  (let [tx-ops (body->edn request)
        {:keys [crux.tx/tx-time] :as submitted-tx} (.submitTx crux-system tx-ops)]
    (-> (success-response submitted-tx)
        (assoc :status 202)
        (add-last-modified tx-time))))

;; TODO: Could add from date parameter.
(defn- tx-log [^ICruxAPI crux-system request]
  (let [with-documents? (Boolean/parseBoolean (get-in request [:query-params "with-documents"]))
        from-tx-id (some->> (get-in request [:query-params "from-tx-id"])
                            (Long/parseLong))
        ctx (.newTxLogContext crux-system)
        result (.txLog crux-system ctx from-tx-id with-documents?)]
    (-> (streamed-edn-response ctx result)
        (add-last-modified (tx/latest-completed-tx-time (:crux.tx-log/consumer-state (.status crux-system)))))))

(defn- sync-handler [^ICruxAPI crux-system request]
  (let [timeout (some->> (get-in request [:query-params "timeout"])
                         (Long/parseLong)
                         (Duration/ofMillis))
        last-modified (.sync crux-system timeout)]
    (-> (success-response last-modified)
        (add-last-modified last-modified))))

(defn- stats [^ICruxAPI crux-system]
  (success-response (.stats crux-system)))

(def ^:private sparql-available? (try
                                   (require 'crux.sparql.protocol)
                                   true
                                   (catch IOException _
                                     false)))

;; ---------------------------------------------------
;; Jetty server

(defn- handler [crux-system request]
  (condp check-path request
    [#"^/$" [:get]]
    (status crux-system)

    [#"^/document/.+$" [:get :post]]
    (document crux-system request)

    [#"^/entity$" [:post]]
    (entity crux-system request)

    [#"^/entity-tx$" [:post]]
    (entity-tx crux-system request)

    [#"^/history/.+$" [:get :post]]
    (history crux-system request)

    [#"^/history-range/.+$" [:get]]
    (history-range crux-system request)

    [#"^/history-ascending$" [:post]]
    (history-ascending crux-system request)

    [#"^/history-descending$" [:post]]
    (history-descending crux-system request)

    [#"^/query$" [:post]]
    (query crux-system request)

    [#"^/query-stream$" [:post]]
    (query-stream crux-system request)

    [#"^/stats" [:get]]
    (stats crux-system)

    [#"^/sync$" [:get]]
    (sync-handler crux-system request)

    [#"^/tx-log$" [:get]]
    (tx-log crux-system request)

    [#"^/tx-log$" [:post]]
    (transact crux-system request)

    (if (and (check-path [#"^/sparql/?$" [:get :post]] request)
             sparql-available? )
      ((resolve 'crux.sparql.protocol/sparql-query) crux-system request)
      {:status 400
       :headers {"Content-Type" "text/plain"}
       :body "Unsupported method on this address."})))

(s/def ::server-port :crux.io/port)

(s/def ::options (s/keys :req-un [::server-port]
                         :opt-un [:crux.kafka/bootstrap-servers]))

(defrecord HTTPServer [^Server server options]
  Closeable
  (close [_]
    (.stop server)))

;; TODO: The direct dependency on ClusterNode here will go.

(defn start-http-server
  "Starts a HTTP server listening to the specified server-port, serving
  the Crux HTTP API. Takes a either a crux.api.ICruxAPI or its
  dependencies explicitly as arguments (internal use)."
  (^java.io.Closeable
   [crux-system {:keys [server-port cors-access-control]
                 :or {server-port 3000 cors-acces-control []}
                 :as options}]
   (s/assert ::options options)
   (let [server (j/run-jetty (-> (partial handler crux-system)
                                 (#(apply wrap-cors (into [%] cors-access-control)))
                                 (p/wrap-params)
                                 (wrap-exception-handling))
                             {:port server-port
                              :join? false})]
     (log/info "HTTP server started on port: " server-port)
     (->HTTPServer server options)))
  (^crux.http_server.HTTPServer
   [kv-store tx-log indexer consumer-config {:keys [server-port]
                                             :as options}]
   (start-http-server (b/map->CruxNode {:kv-store kv-store
                                        :tx-log tx-log
                                        :indexer indexer
                                        :consumer-config consumer-config
                                        :options options})
                      options)))
