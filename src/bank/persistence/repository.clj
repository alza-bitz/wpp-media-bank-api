(ns bank.persistence.repository
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defprotocol AccountRepository
  "Repository interface for account operations."
  (save-account [this account])
  (find-account [this account-number])
  (save-account-event [this account event])
  (save-account-events [this account-event-pairs])
  (find-account-events [this account-number]))

(def account-builder rs/as-unqualified-kebab-maps)

(defn prune-nil-keys 
  "Remove keys with nil values from a map"
  [m]
  (into {} (filter (fn [[_k v]] (some? v)) m)))

(defn account-event-builder 
  "Transform result set for account events, converting event_sequence to sequence"
  [result-set options]
  (let [label-fn (fn [label]
                   (if (= "event_sequence" label)
                     "sequence"
                     (str/replace label #"_" "-")))]
    (rs/as-unqualified-modified-maps result-set (assoc options :label-fn label-fn))))

(defn save-account-events-with-retry
  "Common function to save account and event pairs with retry logic for constraint violations."
  [datasource account-event-pairs max-attempts retry-delay-range]
  (loop [attempt 1]
    (let [result (try
                   (when (> attempt 1)
                     (log/infof "Retrying transaction, attempt %d" attempt))
                   (jdbc/with-transaction [tx datasource]
                     ;; Process all account-event pairs in the same transaction
                     (doall
                       (for [{:keys [account event]} account-event-pairs]
                         (do
                           ;; First update the account
                           (sql/update! tx :account
                                        (select-keys account [:balance])
                                        {:account_number (:account-number account)})
                           ;; Then insert the event using per-account sequence number
                           (-> (jdbc/execute-one! tx
                                                  ["INSERT INTO account_event (id, event_sequence, account_number, description, timestamp, debit, credit) 
                                                       VALUES (?, (SELECT COALESCE(MAX(event_sequence), 0) + 1 FROM account_event WHERE account_number = ?), ?, ?, ?, ?, ?)"
                                                   (:id event)
                                                   (:account-number account)
                                                   (:account-number account)
                                                   (:description event)
                                                   (java.sql.Timestamp/from (:timestamp event))
                                                   (:debit (:action event))
                                                   (:credit (:action event))]
                                                  {:return-keys true
                                                   :builder-fn account-event-builder})
                               prune-nil-keys)))))
                   (catch org.postgresql.util.PSQLException e
                     (log/warnf "Failed to commit transaction on attempt %d: %s" attempt (.getMessage e))
                     (if (and (< attempt max-attempts)
                              (= (.getState org.postgresql.util.PSQLState/UNIQUE_VIOLATION)
                                 (.getSQLState e)))
                       (do
                         (Thread/sleep (rand-int retry-delay-range))
                         ::retry)
                       (do
                         (log/errorf "Failed to commit transaction after maximum %d attempts, rethrowing: %s" attempt (.getMessage e))
                         (throw e)))))]
      (if (= result ::retry)
        (recur (inc attempt))
        result))))

(defrecord JdbcAccountRepository [datasource]
  AccountRepository

  (save-account [_ account]
    (jdbc/with-transaction [tx datasource]
      (sql/insert! tx :account account {:return-keys true
                                        :builder-fn account-builder})))

  (find-account [_ account-number]
    (or (sql/get-by-id datasource :account account-number :account_number {:builder-fn account-builder})
        (throw (ex-info "Account not found" {:error :account-not-found
                                             :message "Account not found"
                                             :account-number account-number}))))

  (save-account-event
   [_ account event]
   (first (save-account-events-with-retry datasource [{:account account :event event}] 3 50)))

  (save-account-events
   [_ account-event-pairs]
   (save-account-events-with-retry datasource account-event-pairs 3 50))

  (find-account-events [_ account-number]
                       (->> (sql/query datasource
                                       ["SELECT id, event_sequence, account_number, description, timestamp, debit, credit FROM account_event WHERE account_number = ? ORDER BY event_sequence DESC"
                                        account-number]
                                       {:builder-fn account-event-builder})
                            (mapv prune-nil-keys))))

;; Database schema functions
(defn create-tables!
  "Creates the account and account_event tables."
  [datasource]
  (let [logging-datasource (jdbc/with-logging datasource #(log/debug {:op %1 :sql %2}))] 
    (jdbc/execute! logging-datasource
                   ["CREATE TABLE IF NOT EXISTS account (
        id UUID PRIMARY KEY,
        account_number SERIAL NOT NULL UNIQUE,
        name VARCHAR(255) NOT NULL,
        balance INTEGER NOT NULL
      )"])
    (jdbc/execute! logging-datasource
                   ["CREATE TABLE IF NOT EXISTS account_event (
        id UUID PRIMARY KEY,
        event_sequence INTEGER NOT NULL,
        account_number INTEGER NOT NULL REFERENCES account(account_number),
        description VARCHAR(255) NOT NULL,
        timestamp TIMESTAMP NOT NULL,
        debit INTEGER,
        credit INTEGER,
        UNIQUE(event_sequence, account_number)
      )"])))

(defn drop-tables!
  "Drops the account and account_event tables."
  [datasource]
  (let [logging-datasource (jdbc/with-logging datasource #(log/debug {:op %1 :sql %2}))] 
    (jdbc/execute! logging-datasource ["DROP TABLE IF EXISTS account_event"])
    (jdbc/execute! logging-datasource ["DROP TABLE IF EXISTS account"])))

(defn logging-jdbc-account-repository
  "Creates a JdbcAccountRepository with logging wrapped datasource."
  [datasource]
  (->JdbcAccountRepository
   (jdbc/with-logging datasource #(log/debug {:op %1 :sql %2}))))

;; Integrant methods
(defmethod ig/init-key ::repository [_ {:keys [datasource]}]
  (logging-jdbc-account-repository datasource))

(defmethod ig/halt-key! ::repository [_ _]
  ;; No cleanup needed for repository
  nil)
