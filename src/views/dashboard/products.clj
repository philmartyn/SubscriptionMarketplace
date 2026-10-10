(ns views.dashboard.products
  "The vendor's product management page: a grid of what is listed, and a modal
  form for adding or editing one.

  The page carries two htmx targets:

    #vendor-products  the grid, the add button and any flash. Replaced whole
                      after a create, edit or delete so the grid and the button
                      stay in step.
    #modal            empty until an Add or Edit link loads the form into it.
                      The form posts back to #modal, so a validation failure
                      re-renders the modal in place and a success empties it
                      while swapping the grid out of band.

  A list-view toggle is anticipated but not built: the grid lives in its own
  function so a second rendering can share the data and the page shell.

  These functions only render. The handler owns the guard and decides what a
  fragment is allowed to say."
  (:require
   [clojure.string :as str]
   [views.htmx :as htmx]
   [views.layout :as layout]))

(defn cents->decimal
  "1250 -> \"12.50\". Locale pinned to ROOT so a comma-decimal locale does not
  produce a value the price parser then rejects."
  [cents]
  (when (some? cents)
    (String/format java.util.Locale/ROOT "%.2f" (object-array [(/ (double cents) 100.0)]))))

(def ^:private interval-options
  [["weekly" "Weekly"] ["monthly" "Monthly"] ["yearly" "Yearly"]])

(def ^:private interval-labels
  (into {} interval-options))

(defn format-price
  "A stored product's price as the card shows it: \"12.50 EUR\". The interval or
  intervals are shown separately, because there may be none."
  [product]
  (str (cents->decimal (:price_cents product)) " " (:currency product)))

(defn selected-intervals
  "The intervals a form should show as checked.

  Submitted values may be a single string, a vector, or absent; a stored product
  carries a vector of strings. All collapse to a set."
  [values]
  (let [raw (:billing-intervals values)]
    (cond
      (nil? raw)    #{}
      (string? raw) #{raw}
      :else         (set raw))))

(defn product->values
  "A stored product in the shape the form round-trips through, so the edit modal
  can prefill it with the same keys submitted values use."
  [product]
  (when product
    {:name              (:name product)
     :description       (:description product)
     :price             (cents->decimal (:price_cents product))
     :currency          (:currency product)
     :billing-intervals (vec (:billing_intervals product))
     :category          (:category product)
     :image-url         (:image_url product)}))

(defn- field
  "A labelled control with its error underneath, the shape views.auth uses."
  [label control error]
  [:label {:class "flex w-full flex-col gap-1"}
   [:span {:class "text-sm font-medium"} label]
   control
   (when error
     [:span {:class "mt-1 text-sm text-error"} error])])

(defn- close-modal-attrs
  "Clears the modal container, closing whatever it holds."
  []
  {:hx-on:click "document.getElementById('modal').innerHTML = ''"})

(defn- backdrop-close-attrs
  "Closes the modal only when the backdrop itself, not its contents, is clicked."
  []
  {:hx-on:click "if (event.target === this) document.getElementById('modal').innerHTML = ''"})

(defn product-form
  "The create/edit form.

  In a modal the form posts over htmx into #modal; on a standalone page it is a
  plain form, so the page still works with scripting off.
  "
  [{:keys [action modal? values errors form-error submit-label cancel-href]}]
  (let [values   (or values {})
        checked  (selected-intervals values)
        currency (or (:currency values) "EUR")
        error    (fn [k] (get errors k))]
    [:form (cond-> {:id     "product-form"
                    :action action
                    :method "post"}
             modal? (assoc :hx-post   action
                           :hx-target "#modal"
                           :hx-swap   "innerHTML"))
     (layout/csrf-field)
     (when form-error
       [:div {:class "alert alert-error mb-4"} [:span form-error]])
     (field "Name"
            [:input (cond-> {:id          "name" :name "name" :type "text"
                             :class       "input w-full"
                             :placeholder "e.g. Weekly vegetable box"
                             :required    true}
                      (some? (:name values)) (assoc :value (:name values)))]
            (error :name))
     (field "Description"
            [:textarea {:id          "description" :name "description"
                        :class       "textarea w-full" :rows 3
                        :placeholder "What is included?"}
             (or (:description values) "")]
            (error :description))
     (field "Price"
            [:input (cond-> {:id          "price" :name "price" :type "number"
                             :step        "0.01" :min "0"
                             :class       "input w-full"
                             :placeholder "12.50"
                             :required    true}
                      (some? (:price values)) (assoc :value (:price values)))]
            (error :price))
     (field "Currency"
            [:select {:id "currency" :name "currency" :class "select w-full"}
             (for [c ["EUR" "USD" "GBP"]]
               ^{:key c}
               [:option (cond-> {:value c}
                          (= c currency) (assoc :selected true))
                c])]
            (error :currency))
     ;; A checkbox group, not a single control, so it does not use the
     ;; :label-wrapped field: labels inside a label are invalid and the
     ;; negative-margin hint sat on top of the boxes. The hint lives inside the
     ;; group instead.
     [:div {:class "flex w-full flex-col gap-1"}
      [:span {:class "text-sm font-medium"} "Billing intervals"]
      [:div {:class "flex flex-wrap gap-4"}
       (for [[value label] interval-options]
         ^{:key value}
         [:label {:class "flex cursor-pointer items-center gap-2"}
          [:input {:type    "checkbox" :name "billing-intervals" :value value
                   :class   "checkbox checkbox-sm"
                   :checked (contains? checked value)}]
          [:span {:class "text-sm"} label]])]
      [:p {:class "text-xs text-base-content/60"}
       "Tick the schedules you will accept, or leave all unticked to let the customer choose."]
      (when (error :billing-intervals)
        [:span {:class "mt-1 text-sm text-error"} (error :billing-intervals)])]
     (field "Category"
            [:input (cond-> {:id          "category" :name "category" :type "text"
                             :class       "input w-full"
                             :placeholder "e.g. Produce"}
                      (some? (:category values)) (assoc :value (:category values)))]
            (error :category))
     (field "Image URL"
            [:input (cond-> {:id          "image-url" :name "image-url" :type "url"
                             :class       "input w-full"
                             :placeholder "https://example.com/image.png"}
                      (some? (:image-url values)) (assoc :value (:image-url values)))]
            (error :image-url))
     [:div {:class "mt-6 flex items-center gap-2"}
      [:button {:type "submit" :class "btn btn-primary"} submit-label]
      (cond
        modal?      [:button (merge {:type "button" :class "btn btn-ghost"}
                                    (close-modal-attrs))
                     "Cancel"]
        cancel-href [:a {:href cancel-href :class "btn btn-ghost"} "Cancel"])]]))

(defn- product-initial
  "The fallback tile shown when a product has no image."
  [name]
  (when (and name (not (str/blank? name)))
    (str/upper-case (subs name 0 1))))

(defn- card-action-form
  "The delete control. Delete is a POST because an HTML form cannot send DELETE."
  [product]
  [:form {:action     (str "/vendor/products/" (:id product) "/delete")
          :method     "post"
          :hx-post    (str "/vendor/products/" (:id product) "/delete")
          :hx-target  "#vendor-products"
          :hx-swap    "outerHTML"
          :hx-confirm "Delete this product?"}
   (layout/csrf-field)
   [:button {:type "submit" :class "btn btn-ghost btn-sm text-error"} "Delete"]])

(defn- card-active-form
  "The activate/deactivate control. A POST carrying the state to move to, so it
  is idempotent rather than a blind flip of whatever the server currently holds."
  [product]
  (let [active? (:is_active product)]
    [:form {:action     (str "/vendor/products/" (:id product) "/active")
            :method     "post"
            :hx-post    (str "/vendor/products/" (:id product) "/active")
            :hx-target  "#vendor-products"
            :hx-swap    "outerHTML"}
     (layout/csrf-field)
     [:input {:type "hidden" :name "active" :value (if active? "false" "true")}]
     [:button {:type "submit" :class "btn btn-ghost btn-sm"}
      (if active? "Deactivate" "Activate")]]))

(defn- product-card
  [product]
  [:div {:class "card border border-base-300 bg-base-100"}
   (if-let [image (:image_url product)]
     [:figure {:class "aspect-video overflow-hidden"}
      [:img {:src image :alt "" :class "h-full w-full object-cover"}]]
     [:div {:class "grid aspect-video place-items-center bg-base-200 text-base-content/30"}
      [:span {:class "text-4xl font-bold"} (product-initial (:name product))]])
   [:div {:class "card-body gap-2 p-4"}
    [:div {:class "flex items-start justify-between gap-2"}
     [:h3 {:class "text-base leading-tight font-semibold break-words"} (:name product)]
     (if (:is_active product)
       [:span {:class "badge badge-success badge-sm"} "Active"]
       [:span {:class "badge badge-ghost badge-sm"} "Inactive"])]
    [:div {:class "flex flex-wrap items-center gap-2"}
     [:span {:class "text-lg font-semibold"} (format-price product)]
     (if (seq (:billing_intervals product))
       (for [interval (:billing_intervals product)]
         ^{:key interval}
         [:span {:class "badge badge-ghost badge-sm"} (get interval-labels interval interval)])
       [:span {:class "badge badge-ghost badge-sm"} "Customer chooses"])]
    [:div {:class "card-actions mt-2 justify-end"}
     [:a {:href     (str "/vendor/products/" (:id product) "/edit")
          :hx-get   (str "/vendor/products/" (:id product) "/edit")
          :hx-target "#modal"
          :hx-swap   "innerHTML"
          :class    "btn btn-ghost btn-sm"}
      "Edit"]
     (card-active-form product)
     (card-action-form product)]]])

(defn- products-grid
  [products]
  (if (seq products)
    [:div {:class "grid grid-cols-1 gap-4 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5"}
     (for [product products]
       ^{:key (:id product)}
       (product-card product))]
    [:div {:class "rounded-box border border-dashed border-base-300 p-10 text-center"}
     [:p {:class "font-medium"} "You have not listed any products yet."]
     [:p {:class "mt-1 text-sm text-base-content/60"} "Add one to start selling."]]))

(defn products-section
  "The #vendor-products wrapper: the grid and the add button underneath it.

  Rendered with :oob? when it is the out-of-band half of a create or edit
  response, where the primary response clears the modal."
  [{:keys [verified? products error oob?]}]
  [:div (cond-> {:id "vendor-products" :class "space-y-6"}
          oob? (assoc :hx-swap-oob "true"))
   (when error
     [:div {:class "alert alert-error"} [:span error]])
   (when-not verified?
     [:div {:class "alert alert-info"}
      [:span "Verify your email address before listing a product. "]
      [:a {:href "/vendor/dashboard" :class "link"} "Resend the verification email"]])
   (products-grid products)
   (when verified?
     [:div {:class "flex justify-start"}
      [:a {:href     "/vendor/products/new"
           :hx-get   "/vendor/products/new"
           :hx-target "#modal"
           :hx-swap   "innerHTML"
           :class    "btn btn-primary"}
       "Add a product"]])])

(defn products-page
  "A complete document for GET /vendor/products."
  [opts]
  (layout/page
   {:title       (str "Your products - " layout/site-name)
    :description "List and manage the products you offer."}
   [:div {:class "w-full px-4 py-10"}
    [:div {:class "mb-6 flex items-center justify-between gap-4"}
     [:h1 {:class "text-2xl font-bold"} "Your products"]
     [:a {:href "/vendor/dashboard" :class "link link-primary text-sm"}
      "Back to dashboard"]]
    (products-section opts)
    ;; Empty until an Add or Edit link loads the form into it.
    [:div {:id "modal"}]]))

(defn section-fragment
  "Just the section. The response htmx swaps in after a delete. A :toast adds
  the transient confirmation as an out-of-band append."
  [opts]
  (htmx/pagelet
   {}
   (products-section opts)
   (when (:toast opts) (layout/toast (:toast opts)))))

(defn section-oob-fragment
  "The response to a successful create or edit.

  The form posts into #modal, so the primary content of this reply is what
  replaces the modal. It is a bare, empty element: that is what closes the
  overlay, and it is non-empty markup so htmx always performs the swap. The
  refreshed grid rides along as an out-of-band swap, with the confirmation as
  another."
  [opts]
  (htmx/pagelet
   {}
   [:div]
   (products-section (assoc opts :oob? true))
   (when (:toast opts) (layout/toast (:toast opts)))))

(defn modal-fragment
  "The modal overlay and form, loaded into #modal.

  Closing is pure markup plus htmx: the backdrop, the close button and Cancel
  all clear #modal, which removes the overlay."
  [{:keys [title] :as opts}]
  (htmx/pagelet
   {}
   [:div (merge {:class "fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/50 p-4 sm:items-center"
                 :role  "dialog" :aria-modal "true"}
                (backdrop-close-attrs))
    [:div {:class "my-8 w-full max-w-2xl rounded-box border border-base-300 bg-base-100 p-6 shadow-xl"}
     [:div {:class "mb-4 flex items-center justify-between"}
      [:h2 {:class "text-lg font-bold"} title]
      [:button (merge {:type "button" :class "btn btn-ghost btn-sm btn-circle"
                       :aria-label "Close"}
                      (close-modal-attrs))
       "✕"]]
     (product-form (assoc opts :modal? true))]]))

(defn form-page
  "A standalone create/edit document, for a browser with no htmx. The form here
  is a plain one that posts and redirects."
  [{:keys [heading] :as opts}]
  (layout/page
   {:title       (str heading " - " layout/site-name)
    :description "Manage the products you offer."}
   [:div {:class "mx-auto w-full max-w-2xl px-4 py-10"}
    [:div {:class "mb-6 flex items-center justify-between gap-4"}
     [:h1 {:class "text-2xl font-bold"} heading]
     [:a {:href "/vendor/products" :class "link link-primary text-sm"}
      "Back to products"]]
    [:div {:class "card border border-base-300"}
     [:div {:class "card-body"}
      (product-form (assoc opts
                           :modal?      false
                           :cancel-href "/vendor/products"))]]]))
