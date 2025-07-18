(ns bank.application.service-integration-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clj-test-containers.core :as tc]
            [bank.persistence.repository :as repo]
            [bank.application.service :as service]
            [bank.domain.account :as account]))

(def ^:dynamic *datasource* nil)

(def ^:dynamic *repository* nil)

(defn ->datasource [container]
  {:dbtype "postgresql"
   :host "localhost"
   :port (get (:mapped-ports container) 5432)
   :dbname "testdb"
   :user "testuser"
   :password "testpass"})

(use-fixtures :once
  (fn [f]
    (let [container (-> {:image-name "postgres:13"
                         :exposed-ports [5432]
                         :env-vars {"POSTGRES_DB" "testdb"
                                    "POSTGRES_USER" "testuser"
                                    "POSTGRES_PASSWORD" "testpass"}
                         :wait-for {:wait-strategy :port}}
                        tc/create
                        tc/start!)
          datasource (->datasource container)
          repository (repo/logging-jdbc-account-repository datasource)]
      (try
        (binding [*datasource* datasource
                  *repository* repository]
          (f))
        (finally
          (tc/stop! container))))))

(use-fixtures :each
  (fn [f]
    (try
      (repo/create-tables! *datasource*)
      (f)
      (finally
        (repo/drop-tables! *datasource*)))))

(deftest account-service-integration-test
  (testing "create account end-to-end"
    (let [service (service/->SyncAccountService *repository*)
          created-account (service/create-account service "Mr. Black")]
      (is (account/valid-saved-account? created-account))
      (is (= "Mr. Black" (:name created-account)))
      (is (= 0 (:balance created-account)))
      (is (pos? (:account-number created-account)))))

  (testing "retrieve account end-to-end"
    (let [service (service/->SyncAccountService *repository*)
          created-account (service/create-account service "Mr. White")
          retrieved-account (service/retrieve-account service (:account-number created-account))]
      (is (= created-account retrieved-account))))

  (testing "deposit to account end-to-end"
    (let [service (service/->SyncAccountService *repository*)
          created-account (service/create-account service "Mr. Green")
          account-number (:account-number created-account)
          updated-account (service/deposit-to-account service account-number 100)]
      (is (account/valid-saved-account? updated-account))
      (is (= "Mr. Green" (:name updated-account)))
      (is (= 100 (:balance updated-account)))
      (is (= account-number (:account-number updated-account)))
      
      ;; Verify the account was updated in the database
      (let [retrieved-account (service/retrieve-account service account-number)]
        (is (= 100 (:balance retrieved-account))))))

  (testing "deposit to non-existent account throws exception"
    (let [service (service/->SyncAccountService *repository*)] 
      (is (thrown-with-msg? 
           clojure.lang.ExceptionInfo 
           #"Account not found"
           (service/deposit-to-account service 999999 100)))))

  (testing "deposit negative amount fails"
    (let [service (service/->SyncAccountService *repository*)
          created-account (service/create-account service "Mr. Blue")]
      (is (thrown? AssertionError
                   (service/deposit-to-account service (:account-number created-account) -50)))))

  (testing "multiple deposits accumulate correctly"
    (let [service (service/->SyncAccountService *repository*)
          created-account (service/create-account service "Mr. Yellow")
          account-number (:account-number created-account)]
      (service/deposit-to-account service account-number 50)
      (service/deposit-to-account service account-number 30)
      (let [final-account (service/retrieve-account service account-number)]
        (is (= 80 (:balance final-account)))))))

(deftest account-audit-service-integration-test
  (testing "retrieve account audit end-to-end"
    (let [service (service/->SyncAccountService *repository*)
          created-account (service/create-account service "Audit Test User")
          account-number (:account-number created-account)]
      
      ;; Initially, audit log should be empty
      (let [initial-events (service/retrieve-account-audit service account-number)]
        (is (empty? initial-events)))
      
      ;; Perform some transactions
      (service/deposit-to-account service account-number 100)
      (service/deposit-to-account service account-number 50)
      (service/withdraw-from-account service account-number 25)
      
      ;; Check audit log
      (let [events (service/retrieve-account-audit service account-number)]
        (is (= 3 (count events)))
        
        ;; Should be in reverse chronological order
        (is (= 3 (:sequence (first events)))) ; withdraw event (latest)
        (is (= 2 (:sequence (second events)))) ; second deposit event
        (is (= 1 (:sequence (nth events 2)))) ; first deposit event (earliest)
        
        ;; Verify event details
        (is (= "withdraw" (:description (first events))))
        (is (= 25 (:debit (first events))))
        (is (nil? (:credit (first events))))
        
        (is (= "deposit" (:description (second events))))
        (is (= 50 (:credit (second events))))
        (is (nil? (:debit (second events))))
        
        (is (= "deposit" (:description (nth events 2))))
        (is (= 100 (:credit (nth events 2))))
        (is (nil? (:debit (nth events 2))))
        
        ;; All events should belong to the same account
        (is (every? #(= account-number (:account-number %)) events)))))

  (testing "retrieve account audit for non-existent account throws exception"
    (let [service (service/->SyncAccountService *repository*)]
      (is (thrown-with-msg? 
           clojure.lang.ExceptionInfo 
           #"Account not found"
           (service/retrieve-account-audit service 999999))))))

(deftest transfer-between-accounts-service-integration-test
  (testing "transfer money between accounts end-to-end"
    (let [service (service/->SyncAccountService *repository*)
          sender-account (service/create-account service "Sender")
          receiver-account (service/create-account service "Receiver")
          sender-number (:account-number sender-account)
          receiver-number (:account-number receiver-account)]

      ;; Initial setup - deposit to sender
      (service/deposit-to-account service sender-number 100)

      ;; Transfer money
      (let [result (service/transfer-between-accounts service sender-number receiver-number 30)
            updated-sender (:sender result)
            updated-receiver (:receiver result)]
        (is (= 70 (:balance updated-sender)))
        (is (= sender-number (:account-number updated-sender)))
        (is (= 30 (:balance updated-receiver)))
        (is (= receiver-number (:account-number updated-receiver)))

        ;; Check audit logs
        (let [sender-events (service/retrieve-account-audit service sender-number)
              receiver-events (service/retrieve-account-audit service receiver-number)]
          
          ;; Sender should have deposit and send events
          (is (= 2 (count sender-events)))
          (is (= "send to #2" (:description (first sender-events))))
          (is (= 30 (:debit (first sender-events))))
          (is (= "deposit" (:description (second sender-events))))
          
          ;; Receiver should have receive event
          (is (= 1 (count receiver-events)))
          (is (= "receive from #1" (:description (first receiver-events))))
          (is (= 30 (:credit (first receiver-events))))))))

  (testing "transfer with insufficient funds fails"
    (let [service (service/->SyncAccountService *repository*)
          sender-account (service/create-account service "Poor Sender")
          receiver-account (service/create-account service "Receiver")
          sender-number (:account-number sender-account)
          receiver-number (:account-number receiver-account)]

      ;; Only deposit 50
      (service/deposit-to-account service sender-number 50)

      ;; Try to transfer 100 - should fail
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Insufficient funds"
           (service/transfer-between-accounts service sender-number receiver-number 100)))))

  (testing "transfer to same account fails"
    (let [service (service/->SyncAccountService *repository*)
          account (service/create-account service "Self Transfer")
          account-number (:account-number account)]

      (service/deposit-to-account service account-number 100)

      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Cannot transfer to same account"
           (service/transfer-between-accounts service account-number account-number 50)))))

  (testing "transfer to non-existent account fails"
    (let [service (service/->SyncAccountService *repository*)
          sender-account (service/create-account service "Sender")
          sender-number (:account-number sender-account)]

      (service/deposit-to-account service sender-number 100)

      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Account not found"
           (service/transfer-between-accounts service sender-number 999999 50)))))

  (testing "transfer from non-existent account fails"
    (let [service (service/->SyncAccountService *repository*)
          receiver-account (service/create-account service "Receiver")
          receiver-number (:account-number receiver-account)]

      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Account not found"
           (service/transfer-between-accounts service 999999 receiver-number 50))))))

(deftest async-account-service-integration-test
  (testing "async create account end-to-end"
    (let [async-service (service/consumer-pool-async-account-service *repository* 5)]
      
      (try
        ;; Call create-account protocol function directly
        (let [operation-id (service/create-account async-service "Mr. Async")]
          
          ;; Retrieve operation result
          (let [created-account (service/retrieve-operation-result async-service operation-id)]
            (is (not (nil? created-account)))
            (is (= "Mr. Async" (:name created-account)))
            (is (= 0 (:balance created-account)))
            (is (> (:account-number created-account) 0))))
        
        (finally
          (service/stop async-service))))

  (testing "async retrieve account end-to-end"
    (let [async-service (service/consumer-pool-async-account-service *repository* 5)
          sync-service (service/->SyncAccountService *repository*)]
      
      (try
        ;; First create an account synchronously
        (let [created-account (service/create-account sync-service "Mr. Retrieve")
              account-number (:account-number created-account)]
          
          ;; Call retrieve-account protocol function directly
          (let [operation-id (service/retrieve-account async-service account-number)]
            
            ;; Retrieve operation result
            (let [retrieved-account (service/retrieve-operation-result async-service operation-id)]
              (is (not (nil? retrieved-account)))
              (is (= "Mr. Retrieve" (:name retrieved-account)))
              (is (= 0 (:balance retrieved-account)))
              (is (= account-number (:account-number retrieved-account))))))
        
        (finally (service/stop async-service))))

  (testing "concurrent create account operations using Malli generators"
    (let [async-service (service/consumer-pool-async-account-service *repository* 10)]
      
      (try
        ;; Generate test data using Malli generator
        (let [account-names (take 20 (repeatedly account/gen-account-name))
              operation-ids (mapv (fn [name]
                                    (service/create-account async-service name))
                                  account-names)]
          
          ;; Retrieve all results
          (let [created-accounts (mapv #(service/retrieve-operation-result async-service %) operation-ids)]
            
            ;; Verify all accounts were created
            (is (= 20 (count created-accounts)))
            (is (every? #(not (nil? %)) created-accounts))
            (is (every? #(= 0 (:balance %)) created-accounts))
            (is (every? #(> (:account-number %) 0) created-accounts))
            
            ;; Verify account numbers are unique
            (let [account-numbers (map :account-number created-accounts)]
              (is (= (count account-numbers) (count (set account-numbers)))))
            
            ;; Verify names match (though order may be different due to concurrency)
            (let [created-names (map :name created-accounts)
                  expected-names (set account-names)]
              (is (= expected-names (set created-names))))))
        
        (finally (service/stop async-service)))))

  (testing "async comprehensive operations test"
    (let [async-service (service/consumer-pool-async-account-service *repository* 5)]
      
      (try
        ;; Create two accounts for transfer test using protocol functions
        (let [sender-op-id (service/create-account async-service "Async Sender")
              receiver-op-id (service/create-account async-service "Async Receiver")
              
              sender-account (service/retrieve-operation-result async-service sender-op-id)
              receiver-account (service/retrieve-operation-result async-service receiver-op-id)
              sender-number (:account-number sender-account)
              receiver-number (:account-number receiver-account)]
          
          ;; Test async deposit
          (let [deposit-op-id (service/deposit-to-account async-service sender-number 100)
                updated-sender (service/retrieve-operation-result async-service deposit-op-id)]
            (is (= 100 (:balance updated-sender)))
            (is (= "Async Sender" (:name updated-sender))))
          
          ;; Test async transfer
          (let [transfer-op-id (service/transfer-between-accounts async-service sender-number receiver-number 30)
                transfer-result (service/retrieve-operation-result async-service transfer-op-id)]
            (is (= 70 (:balance (:sender transfer-result))))
            (is (= 30 (:balance (:receiver transfer-result)))))
          
          ;; Test async withdraw  
          (let [withdraw-op-id (service/withdraw-from-account async-service sender-number 20)
                updated-sender (service/retrieve-operation-result async-service withdraw-op-id)]
            (is (= 50 (:balance updated-sender))))
          
          ;; Test async audit
          (let [audit-op-id (service/retrieve-account-audit async-service sender-number)
                audit-events (service/retrieve-operation-result async-service audit-op-id)]
            (is (= 3 (count audit-events))) ; deposit, transfer, withdraw
            (is (= "withdraw" (:description (first audit-events))))
            (is (.startsWith (:description (second audit-events)) "send to #"))  
            (is (= "deposit" (:description (nth audit-events 2))))))
        
        (finally (service/stop async-service))))))))