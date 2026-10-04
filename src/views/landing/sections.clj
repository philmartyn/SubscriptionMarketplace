(ns views.landing.sections
  "One function per landing page section. Each returns a Hiccup vector and reads
  its copy from views.landing.content."
  (:require
   [views.landing.content :as content]))

(defn section
  "Wraps content in a full-width padded section with an optional id, used for
  in-page anchor targets."
  [{:keys [id class]} & body]
  [:section {:id id
             :class (str "mx-auto w-full max-w-6xl px-4 sm:px-6 lg:px-8 " class)}
   body])

(defn section-heading
  [{:keys [title body center]}]
  [:div (cond-> {:class "max-w-2xl"}
          center (assoc :class "mx-auto max-w-2xl text-center"))
   [:h2 {:class "text-3xl font-bold tracking-tight sm:text-4xl"} title]
   (when body
     [:p {:class "mt-4 text-base-content/70"} body])])

(defn hero
  "Above-the-fold pitch."
  []
  (section
   {:class "pt-16 pb-20 sm:pt-24 sm:pb-28"}
   [:div {:class "grid items-center gap-12 lg:grid-cols-2"}
    [:div
     [:div {:class "badge badge-outline badge-lg gap-2"}
      [:span {:class "size-1.5 rounded-full bg-success"}]
      (:eyebrow content/hero)]
     [:h1 {:class "mt-6 text-4xl font-bold tracking-tight text-balance sm:text-5xl lg:text-6xl"}
      (:title content/hero)]
     [:p {:class "mt-6 text-lg text-base-content/70"}
      (:body content/hero)]
     [:div {:class "mt-8 flex flex-wrap gap-3"}
      [:a {:href (:href (:primary content/hero))
           :class "btn btn-primary btn-lg"}
       (:label (:primary content/hero))]
      [:a {:href (:href (:secondary content/hero))
           :class "btn btn-outline btn-lg"}
       (:label (:secondary content/hero))]]
     [:div {:class "stats stats-vertical mt-10 border border-base-300 sm:stats-horizontal"}
      (for [{:keys [value label]} content/stats]
        ^{:keys [value label]}
        [:div {:class "stat px-6 py-4"}
         [:div {:class "stat-title text-xs uppercase tracking-wide"} label]
         [:div {:class "stat-value text-2xl"} value]])]]
    [:div {:class "hidden lg:block"}
     [:div {:class "card w-full max-w-sm border border-base-300 bg-base-200/60 shadow-xl"}
      [:div {:class "card-body gap-4"}
       [:div {:class "flex items-center justify-between"}
        [:h3 {:class "card-title text-base"} "Your plan, on repeat"]
        [:span {:class "badge badge-primary badge-sm"} "Live"]]
       [:div {:class "rounded-box bg-base-100 p-4 shadow-sm"}
        [:p {:class "text-xs uppercase tracking-wide opacity-60"} "Next delivery"]
        [:p {:class "mt-1 text-3xl font-bold"} "☕"]
        [:p {:class "mt-1 font-medium"} "Daily Roast - one coffee a day"]
        [:p {:class "text-sm opacity-70"} "€50 / month - 30 deliveries"]
        [:div {:class "mt-4 flex items-center justify-between text-sm"}
         [:span {:class "opacity-60"} "Renews"]
         [:span {:class "font-medium"} "1 Nov"]]
        [:div {:class "mt-4 flex gap-2"}
         [:button {:class "btn btn-sm flex-1"} "Pause"]
         [:button {:class "btn btn-sm btn-outline flex-1"} "Skip"]]]]]]]))

(defn trust-bar
  "Placeholder social proof. Replace with real logos or customer names."
  []
  [:div {:class "border-y border-base-300 bg-base-200/40 py-6"}
   [:div {:class "mx-auto w-full max-w-6xl px-4 sm:px-6 lg:px-8"}
    [:p {:class "text-center text-xs font-medium uppercase tracking-widest opacity-50"}
     "Businesses already selling on a schedule"]
    [:div {:class "mt-4 flex flex-wrap items-center justify-center gap-x-8 gap-y-3 sm:gap-x-12"}
     (for [name content/trusted-by]
       ^{:key name}
       [:span {:class "text-sm font-semibold opacity-60 sm:text-base"} name])]]])

(defn categories
  "Grid showing that any business can list a plan."
  []
  (section
   {:id "categories" :class "py-20"}
   (section-heading
    {:title "Whatever you sell, it can repeat"
     :body "Subscriptions are not just for boxes. If your business produces something on a schedule, it belongs here."
     :center true})
   [:div {:class "mt-12 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4"}
    (for [{:keys [icon name blurb]} content/categories]
      ^{:keys [icon name blurb]}
      [:div {:class "card card-border bg-base-100 transition hover:border-primary hover:shadow-md"}
       [:div {:class "card-body p-5"}
        [:div {:class "text-3xl"} icon]
        [:h3 {:class "card-title text-base"} name]
        [:p {:class "text-sm text-base-content/60"} blurb]]])]))

(defn plans
  "Example listings. The featured card is the one-coffee-a-day example."
  []
  (section
   {:id "plans" :class "py-20"}
   (section-heading
    {:title "Example plans"
     :body "Real listings will look like this. Every plan shows its price and cadence before you subscribe."
     :center true})
   [:div {:class "mt-12 grid gap-6 lg:grid-cols-3"}
    (for [{:keys [name vendor tagline price unit badge featured features]} content/plans]
      ^{:keys [name vendor tagline price unit badge featured features]}
      [:div (cond-> {:class "card border bg-base-100"}
              featured (assoc :class "card border border-primary shadow-lg lg:-mt-4 lg:mb-4"))
       [:div {:class "card-body"}
        (when badge
          [:div {:class "badge badge-primary"} badge])
        [:p {:class "text-xs uppercase tracking-wide opacity-60"} vendor]
        [:h3 {:class "card-title text-2xl"} name]
        [:p {:class "text-sm text-base-content/70"} tagline]
        [:div {:class "mt-2 flex items-baseline gap-1"}
         [:span {:class "text-4xl font-bold"} price]
         [:span {:class "opacity-60"} unit]]
        [:ul {:class "mt-4 flex-1 space-y-2 text-sm"}
         (for [feature features]
           ^{:key feature}
           [:li {:class "flex gap-2"}
            [:span {:class "text-success"} "✓"]
            [:span feature]])]
        [:a {:href "/auth/signup"
             :class (str "btn btn-block mt-6 " (if featured "btn-primary" "btn-outline"))}
         "Subscribe"]]])]))

(defn how-it-works []
  (section
   {:id "how-it-works" :class "py-20"}
   (section-heading
    {:title "How it works"
     :body "Three steps, and most of the work is done before you ever get an order."
     :center true})
   [:div {:class "mt-12 grid gap-8 md:grid-cols-3"}
    (for [[index step] (map-indexed vector content/how-it-works)]
      ^{:key (:title step)}
      [:div {:class "flex flex-col items-center text-center"}
       [:div {:class "grid size-12 place-items-center rounded-full bg-primary font-bold text-primary-content"}
        (inc index)]
       [:h3 {:class "mt-4 text-lg font-semibold"} (:title step)]
       [:p {:class "mt-2 text-sm text-base-content/70"} (:body step)]])]))

(defn for-business []
  (let [{:keys [title body points cta]} content/for-business]
    (section
     {:id "for-business" :class "py-20"}
     [:div {:class "grid items-center gap-12 lg:grid-cols-2"}
      [:div
       [:p {:class "text-sm font-semibold uppercase tracking-widest text-primary"} "For businesses"]
       [:h2 {:class "mt-3 text-3xl font-bold tracking-tight sm:text-4xl"} title]
       [:p {:class "mt-4 text-base-content/70"} body]
       [:a {:href (:href cta) :class "btn btn-primary mt-8"} (:label cta)]]
      [:div {:class "grid gap-4 sm:grid-cols-2"}
       (for [{:keys [title body]} points]
         ^{:keys [title body]}
         [:div {:class "card card-border bg-base-100"}
          [:div {:class "card-body p-5"}
           [:h3 {:class "card-title text-base"} title]
           [:p {:class "text-sm text-base-content/60"} body]]])]])))

(defn pricing []
  (let [{:keys [title body tiers]} content/pricing]
    (section
     {:id "pricing" :class "py-20"}
     (section-heading {:title title :body body :center true})
     [:div {:class "mt-12 grid gap-6 lg:grid-cols-3"}
      (for [{:keys [name price unit audience featured features]} tiers]
        ^{:keys [name price unit audience featured features]}
        [:div (cond-> {:class "card border bg-base-100"}
                featured (assoc :class "card border border-primary shadow-lg"))
         [:div {:class "card-body"}
          [:p {:class "text-xs uppercase tracking-wide opacity-60"} audience]
          [:h3 {:class "card-title text-xl"} name]
          [:div {:class "mt-1 flex items-baseline gap-1"}
           [:span {:class "text-3xl font-bold"} price]
           (when unit
             [:span {:class "text-sm opacity-60"} unit])]
          [:ul {:class "mt-4 flex-1 space-y-2 text-sm"}
           (for [feature features]
             ^{:key feature}
             [:li {:class "flex gap-2"}
              [:span {:class "text-success"} "✓"]
              [:span feature]])]
          [:a {:href "/auth/signup"
               :class (str "btn btn-block mt-6 " (if featured "btn-primary" "btn-outline"))}
           (if featured "Start selling" "Create account")]]])])))

(defn testimonial []
  (let [{:keys [quote name role]} content/testimonial]
    (section
     {:class "py-20"}
     [:div {:class "card border border-base-300 bg-base-200/50"}
      [:div {:class "card-body items-center text-center"}
       [:div {:class "text-4xl opacity-40"} "❝"]
       [:blockquote {:class "max-w-3xl text-xl leading-relaxed sm:text-2xl"} quote]
       [:div {:class "mt-4"}
        [:p {:class "font-semibold"} name]
        [:p {:class "text-sm opacity-60"} role]]]])))

(defn faq
  "Native <details> elements, so the FAQ works with no JavaScript at all."
  []
  (section
   {:id "faq" :class "py-20"}
   (section-heading
    {:title "Frequently asked questions"
     :body "The things sellers and subscribers ask most."
     :center true})
   [:div {:class "mx-auto mt-12 max-w-3xl space-y-3"}
    (for [{:keys [q a]} content/faq]
      ^{:keys [q a]}
      [:details {:class "collapse collapse-plus border border-base-300 bg-base-100"}
       [:summary {:class "collapse-title text-base font-medium"} q]
       [:div {:class "collapse-content text-sm text-base-content/70"} [:p a]]])]))

(defn final-cta
  "Last call to action before the footer."
  []
  (let [{:keys [title body]} content/final-cta]
    (section
     {:class "py-20"}
     [:div {:class "hero overflow-hidden rounded-box bg-primary text-primary-content"}
      [:div {:class "hero-content w-full flex-col items-center px-6 py-16 text-center"}
       [:div
        [:h2 {:class "text-3xl font-bold tracking-tight sm:text-4xl"} title]
        [:p {:class "mt-3 max-w-xl text-primary-content/80"} body]]
       [:div {:class "mt-8 flex flex-wrap justify-center gap-3"}
        [:a {:href "/auth/signup" :class "btn btn-neutral btn-lg"} "Get started"]
        [:a {:href "/auth/signin" :class "btn btn-outline btn-lg text-primary-content"} "Sign in"]]]])))