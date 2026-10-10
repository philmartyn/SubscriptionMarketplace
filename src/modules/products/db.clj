(ns modules.products.db
  "Data access for vendor products.

  Every function takes the connectable first, like modules.auth.db, so a test
  can hand it the shared datasource and a handler can hand it the same handle
  the session store uses.

  Every read and write is filtered by vendor-id. That filter is the security
  boundary: a vendor can only ever see or change rows it owns, and a guessed
  product id belonging to someone else simply matches nothing.

  A product owns a set of allowed billing intervals in a child table. The set
  may be empty, which means the vendor leaves the schedule to the buyer. Writes
  touch both tables inside one transaction, so a product is never briefly
  listed with the wrong intervals or with none after an edit."
  (:require
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [shared.db :as sdb]))

(def ^:private interval-rank
  "Canonical order for the interval set, so a product's intervals come back in
  the same order a person reads them rather than in whatever order Postgres
  happens to return."
  {"weekly" 0 "monthly" 1 "yearly" 2})

(defn- ordered-intervals [intervals]
  (vec (sort-by #(get interval-rank % 99) intervals)))

(defn- intervals-by-product
  "product id -> the intervals allowed for it, for the products given."
  [db product-ids]
  (if (seq product-ids)
    (reduce (fn [acc {:keys [product_id interval]}]
              (update acc product_id (fnil conj []) interval))
            {}
            (sdb/many db
                      (sql/format {:select [:product_id :interval]
                                   :from   [:product_billing_intervals]
                                   :where  [:in :product_id product-ids]})))
    {}))

(defn- attach-intervals
  [db products]
  (let [by-id (intervals-by-product db (mapv :id products))]
    (mapv #(assoc % :billing_intervals (ordered-intervals (get by-id (:id %) [])))
          products)))

(defn list-products-for-vendor
  "Every product a vendor has listed, active ones first and newest first.

  Two queries rather than an aggregate join: the list is small, and this keeps
  the interval set a plain vector of strings instead of a driver-specific array.
  "
  [db vendor-id]
  (attach-intervals
   db
   (sdb/many db
             (sql/format {:select   [:*]
                          :from     [:products]
                          :where    [:= :vendor_id vendor-id]
                          :order-by [[:is_active :desc]
                                     [:created_at :desc]
                                     [:id :desc]]}))))

(defn find-product-for-vendor
  "One product with its intervals, only if this vendor owns it."
  [db vendor-id product-id]
  (when-let [product (sdb/one db
                              (sql/format {:select [:*]
                                           :from   [:products]
                                           :where  [:and
                                                    [:= :vendor_id vendor-id]
                                                    [:= :id product-id]]}))]
    (first (attach-intervals db [product]))))

(defn- product-columns
  "The submitted values as product columns. Intervals live in their own table."
  [input]
  {:name        (:name input)
   :description (:description input)
   :price_cents (:price-cents input)
   :currency    (:currency input)
   :category    (:category input)
   :image_url   (:image-url input)})

(defn- insert-intervals!
  [tx product-id intervals]
  (when (seq intervals)
    (sdb/many tx
              (sql/format {:insert-into :product_billing_intervals
                           :values      (mapv (fn [interval]
                                                {:product_id product-id
                                                 :interval   interval})
                                              intervals)}))))

(defn- replace-intervals!
  [tx product-id intervals]
  (sdb/affected tx
                (sql/format {:delete-from :product_billing_intervals
                             :where       [:= :product_id product-id]}))
  (insert-intervals! tx product-id intervals))

(defn insert-product!
  "Insert a product and its intervals, and return the stored row.

  RETURNING * rather than a generated-key fetch: it returns every column in one
  round trip and does not depend on the driver's idea of what a generated key
  is."
  [db {:keys [vendor-id billing-intervals] :as input}]
  (jdbc/with-transaction [tx db]
    (let [product (sdb/one tx
                           (sql/format {:insert-into :products
                                        :values      [(assoc (product-columns input)
                                                             :vendor_id vendor-id)]
                                        :returning   [:*]}))]
      (insert-intervals! tx (:id product) billing-intervals)
      (assoc product :billing_intervals (ordered-intervals billing-intervals)))))

(defn update-product!
  "Update a product the vendor owns and replace its intervals, returning the
  updated row or nil.

  nil means the row does not exist or belongs to another vendor; the caller
  cannot tell those apart, and should not."
  [db vendor-id product-id {:keys [billing-intervals] :as input}]
  (jdbc/with-transaction [tx db]
    (when-let [product (sdb/one tx
                                (sql/format {:update    :products
                                             :set       (assoc (product-columns input)
                                                               :updated_at (sdb/now-ts))
                                             :where     [:and
                                                         [:= :vendor_id vendor-id]
                                                         [:= :id product-id]]
                                             :returning [:*]}))]
      (replace-intervals! tx product-id billing-intervals)
      (assoc product :billing_intervals (ordered-intervals billing-intervals)))))

(defn set-product-active!
  "Turn a product the vendor owns on or off, returning the stored row or nil.

  Scoped by vendor-id like every other write, so an id belonging to another
  vendor simply matches nothing. The intervals are attached so the row has the
  same shape every other read produces."
  [db vendor-id product-id active?]
  (when-let [product (sdb/one db
                              (sql/format {:update    :products
                                           :set       {:is_active  active?
                                                       :updated_at (sdb/now-ts)}
                                           :where     [:and
                                                       [:= :vendor_id vendor-id]
                                                       [:= :id product-id]]
                                           :returning [:*]}))]
    (first (attach-intervals db [product]))))

(defn delete-product!
  "Delete a product the vendor owns. Returns rows removed. Its intervals go with
  it through ON DELETE CASCADE."
  [db vendor-id product-id]
  (sdb/affected db
                (sql/format {:delete-from :products
                             :where       [:and
                                           [:= :vendor_id vendor-id]
                                           [:= :id product-id]]})))
