(ns modules.products.service
  "Validation and rules for vendor products.

  The handler layer translates HTTP in and out; this namespace decides what a
  valid product is and answers with a result map rather than throwing, the same
  contract modules.auth.service uses. A failure carries errors keyed by form
  field so the view can render each one next to the input that caused it.

  Submitted values arrive under their form names (:billing-intervals,
  :image-url), because that is what the request parameters hold. The domain
  values the database wants are built once, in normalize."
  (:require
   [clojure.string :as str]
   [modules.products.db :as db]
   [shared.telemetry :as telemetry]))

(def billing-interval-values
  "The values product_billing_intervals.interval accepts, and the set the form
  offers. A product may allow none of them, in which case the buyer chooses."
  #{:weekly :monthly :yearly})

(def ^:private interval-rank
  "Display and storage order, so stored intervals are canonical."
  {:weekly 0 :monthly 1 :yearly 2})

(def currencies
  "The currencies the form offers. Stored as text, so widening this is a code
  change with no migration."
  #{"EUR" "USD" "GBP"})

(def max-name-length 120)

(def max-price-cents
  "A ceiling rather than a business rule: 1,000,000.00 in the smallest unit.
  It stops a pasted-in number from overflowing the INTEGER column, which tops
  out just above 21.4 million."
  100000000)

;;; validation

(defn- name-error
  [name]
  (let [name (str/trim (str name))]
    (cond
      (str/blank? name) "Give the product a name."
      (> (count name) max-name-length)
      (str "Keep the name to " max-name-length " characters or fewer."))))

(defn parse-price-cents
  "A decimal price string to integer cents, or nil when it is not a price.

  Accepts what a person types: 12, 12.5, 12.50. Rejects a sign, a currency
  symbol, more than two decimal places, and anything that would not fit an
  integer."
  [price]
  (let [text (str/trim (str price))]
    (when (re-matches #"\d+(\.\d{1,2})?" text)
      (try
        (.longValueExact (.movePointRight (bigdec text) 2))
        (catch ArithmeticException _ nil)))))

(defn- price-error
  [price]
  (cond
    (str/blank? (str price)) "Set a price."
    (nil? (parse-price-cents price)) "Enter a price like 12.50, with no more than two decimal places."
    (> (parse-price-cents price) max-price-cents) "That price is too high."))

(defn- currency-error
  [currency]
  (when-not (contains? currencies (str/trim (str currency)))
    "Choose a currency."))

(defn- image-url-error
  [image-url]
  (when (and (not (str/blank? (str image-url)))
             (not (re-matches #"https?://\S+" (str/trim (str image-url)))))
    "Enter a link like https://example.com/image.png."))

(defn- interval-values
  "The submitted intervals as a set of keywords.

  The field is a checkbox group: one checked box arrives as a string, several as
  a vector, and none as an absent key. All three are none-or-more intervals.
  Blank entries are dropped so an empty field is simply no restriction."
  [raw]
  (let [values (cond
                 (nil? raw)      []
                 (string? raw)   [raw]
                 :else           (or raw []))]
    (->> values
         (map str/trim)
         (remove str/blank?)
         (map keyword)
         set)))

(defn- billing-intervals-error
  [raw]
  (let [values (interval-values raw)]
    (when (some #(not (contains? billing-interval-values %)) values)
      "Choose from the listed billing intervals.")))

(defn validation-errors
  "Field-keyed errors for a submitted product. Empty when the input is valid."
  [{:keys [name price currency billing-intervals image-url]}]
  (cond-> {}
    (name-error name)
    (assoc :name (name-error name))

    (price-error price)
    (assoc :price (price-error price))

    (currency-error currency)
    (assoc :currency (currency-error currency))

    (billing-intervals-error billing-intervals)
    (assoc :billing-intervals (billing-intervals-error billing-intervals))

    (image-url-error image-url)
    (assoc :image-url (image-url-error image-url))))

(defn- clean
  "A trimmed, non-blank string, or nil. Empty optional fields become NULL
  rather than an empty string, so 'not set' has one representation."
  [value]
  (let [value (str/trim (str value))]
    (when-not (str/blank? value) value)))

(defn normalize
  "Submitted values -> the row shape db/insert-product! and db/update-product!
  expect. Assumes the input already passed validation.

  Intervals come out as a canonical-ordered vector of strings, possibly empty."
  [{:keys [name description price currency billing-intervals category image-url]}]
  {:name              (str/trim (str name))
   :description       (clean description)
   :price-cents       (parse-price-cents price)
   :currency          (str/trim (str currency))
   :billing-intervals (mapv clojure.core/name (sort-by interval-rank (interval-values billing-intervals)))
   :category          (clean category)
   :image-url         (clean image-url)})

;;; operations

(defn list-products
  [db vendor-id]
  (db/list-products-for-vendor db vendor-id))

(defn find-product
  [db vendor-id product-id]
  (db/find-product-for-vendor db vendor-id product-id))

(defn create-product!
  "Create a product for a vendor.

  Returns {:ok? true :product row} or {:ok? false :errors {...}}. Never throws
  for input a caller could have got wrong."
  [db vendor-id input]
  (telemetry/with-span "products.create" {}
    (let [errors (validation-errors input)]
      (if (seq errors)
        (do
          (telemetry/incr! "products.create.rejected" {:reason "validation"})
          {:ok? false :errors errors})
        (let [product (db/insert-product! db (assoc (normalize input) :vendor-id vendor-id))]
          (telemetry/incr! "products.create")
          {:ok? true :product product})))))

(defn update-product!
  "Change a product the vendor owns.

  Returns {:ok? true :product row}, {:ok? false :errors {...}}, or
  {:ok? false :reason :not-found} when no product with that id belongs to the
  vendor."
  [db vendor-id product-id input]
  (telemetry/with-span "products.update" {}
    (let [errors (validation-errors input)]
      (if (seq errors)
        (do
          (telemetry/incr! "products.update.rejected" {:reason "validation"})
          {:ok? false :errors errors})
        (if-let [product (db/update-product! db vendor-id product-id (normalize input))]
          (do
            (telemetry/incr! "products.update")
            {:ok? true :product product})
          {:ok? false :reason :not-found})))))

(defn set-product-active!
  "Turn a product the vendor owns on or off.

  Returns {:ok? true :product row} or {:ok? false :reason :not-found}."
  [db vendor-id product-id active?]
  (telemetry/with-span "products.set-active" {}
    (if-let [product (db/set-product-active! db vendor-id product-id (boolean active?))]
      (do
        (telemetry/incr! "products.set-active" {:active (str (boolean active?))})
        {:ok? true :product product})
      {:ok? false :reason :not-found})))

(defn delete-product!
  "Delete a product the vendor owns.

  Returns {:ok? true} or {:ok? false :reason :not-found}."
  [db vendor-id product-id]
  (telemetry/with-span "products.delete" {}
    (if (pos? (or (db/delete-product! db vendor-id product-id) 0))
      (do
        (telemetry/incr! "products.delete")
        {:ok? true})
      {:ok? false :reason :not-found})))
