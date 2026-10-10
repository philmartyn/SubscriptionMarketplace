(ns modules.vendors.db
  "Data access for vendor profiles.

  The session carries an account-id, not a vendor-id, so every query scoped to
  a vendor starts by resolving the profile that account owns. Keeping that
  lookup here, next to the table it reads, means product code never has to know
  how the two are joined."
  (:require
   [honey.sql :as sql]
   [shared.db :as sdb]))

(defn find-vendor-by-account-id
  "The vendor profile owned by an account, or nil when there is none.

  An account with account_type 'vendor' always has one, written at signup, so a
  nil here means the rows have drifted apart rather than that the caller asked a
  sensible question."
  [db account-id]
  (sdb/one db
           (sql/format {:select [:*]
                        :from   [:vendors]
                        :where  [:= :account_id account-id]})))
