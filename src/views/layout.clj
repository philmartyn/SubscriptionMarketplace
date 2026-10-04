(ns views.layout
  "Shared page shell for every UI page: <head>, navbar, theme switcher, footer.

  Everything that is chrome rather than landing page copy lives here, so
  views.landing.* only has to deal with its own sections."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [views.htmx :as htmx]))

(defn css-href
  "Href for the generated Tailwind bundle.

  output.css is a build artifact that changes on every rebuild of
  tailwind/input.css, and static assets are served without cache headers, so a
  browser will happily reuse a stale copy and the page renders as unstyled
  HTML. Keying the URL on the file's last-modified time gives it a new URL
  after every rebuild, so the current CSS is always fetched.

  Evaluated per request rather than once at load, so a rebuild is picked up
  without restarting the app. Falls back to v=0 when the bundle is missing."
  []
  (str "/output.css?v="
       (or (some-> (io/resource "public/output.css")
                   .openConnection
                   .getLastModified)
           0)))

(defn anchor-attrs
  "Attrs for an internal link.

  hx-boost is inherited from <body>, so hash links opt out with the string
  \"false\". It must be a string: Hiccup drops attributes whose value is the
  boolean false, which would leave the link boosted and re-request the page
  instead of just scrolling to the anchor."
  [href]
  (cond-> {:href href}
    (str/includes? href "#") (assoc :hx-boost "false")))

(def site-name "SubMarket")

(def nav-sections
  [{:id "how-it-works" :label "How it works"}
   {:id "categories" :label "Categories"}
   {:id "plans" :label "Plans"}
   {:id "pricing" :label "Pricing"}
   {:id "faq" :label "FAQ"}])

(def footer-columns
  [{:heading "Marketplace"
    :links [{:label "Browse plans" :href "/#plans"}
            {:label "Categories" :href "/#categories"}
            {:label "How it works" :href "/#how-it-works"}]}
   {:heading "For businesses"
    :links [{:label "List your first plan" :href "/auth/signup"}
            {:label "Pricing" :href "/#pricing"}
            {:label "FAQ" :href "/#faq"}]}
   {:heading "Account"
    :links [{:label "Sign in" :href "/auth/signin"}
            {:label "Create account" :href "/auth/signup"}]}
   {:heading "Company"
    :links [{:label "Terms of service" :href "/terms"}
            {:label "Privacy policy" :href "/privacy"}]}])

(def theme-script
  "Sets data-theme on <html> before first paint (no flash of the wrong theme),
  then toggles it on click and persists the choice.

  Guarded by a window flag so hx-boost body swaps, which re-evaluate inline
  scripts, cannot stack up duplicate listeners. Icon visibility is handled in
  tailwind/input.css off the data-theme attribute, so it stays correct even
  after a swap."
  (str "(function () {"
       "var KEY = 'submarket-theme';"
       "var root = document.documentElement;"
       "root.setAttribute('data-theme', localStorage.getItem(KEY) ||"
       " (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'));"
       "if (window.submarketThemeBound) { return; }"
       "window.submarketThemeBound = true;"
       "document.addEventListener('click', function (e) {"
       "var toggle = e.target.closest && e.target.closest('#theme-toggle');"
       "if (!toggle) { return; }"
       "var next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';"
       "localStorage.setItem(KEY, next);"
       "root.setAttribute('data-theme', next);"
       "});"
       "})();"))

(defn logo []
  [:a {:href "/" :class "btn btn-ghost px-2 normal-case gap-2"}
   [:span {:class "grid size-8 place-items-center rounded-lg bg-primary text-primary-content font-bold"} "S"]
   [:span {:class "text-lg font-semibold tracking-tight"} site-name]])

(defn theme-toggle
  "Toggles daisyUI light/dark. Requires tailwind/input.css, which shows the
  right icon from the data-theme attribute alone."
  []
  [:button {:id "theme-toggle"
            :type "button"
            :class "btn btn-ghost btn-circle btn-sm"
            :title "Toggle light or dark theme"
            :aria-label "Toggle light or dark theme"}
   [:svg {:id "icon-sun"
          :class "size-5"
          :xmlns "http://www.w3.org/2000/svg"
          :fill "none"
          :viewBox "0 0 24 24"
          :stroke-width "1.5"
          :stroke "currentColor"}
    [:path {:stroke-linecap "round" :stroke-linejoin "round"
            :d "M12 3v1.5m0 15V21m9-9h-1.5m-15 0H3m15.364-6.364L17 7m-10 10l-1.364 1.364M18.364 7L17 5.636M7 17l-1.364-1.364"}]]
   [:svg {:id "icon-moon"
          :class "size-5"
          :xmlns "http://www.w3.org/2000/svg"
          :fill "none"
          :viewBox "0 0 24 24"
          :stroke-width "1.5"
          :stroke "currentColor"}
    [:path {:stroke-linecap "round" :stroke-linejoin "round"
            :d "M21.752 15.002A9.72 9.72 0 0118 15.75c-5.385 0-9.75-4.365-9.75-9.75 0-1.33.266-2.597.748-3.752A9.753 9.753 0 003 11.25C3 16.635 7.365 21 12.75 21a9.753 9.753 0 009.002-5.998z"}]]])

(defn navbar
  "Sticky navbar. In-page anchor links opt out of hx-boost so they scroll
  natively instead of triggering a pointless full page swap."
  []
  [:div {:class "navbar sticky top-0 z-50 border-b border-base-300 bg-base-100/85 backdrop-blur"}
   [:div {:class "navbar-start"}
    (logo)]
   [:div {:class "navbar-center hidden lg:flex"}
    [:ul {:class "menu menu-horizontal gap-1 px-1"}
     (for [{:keys [id label]} nav-sections]
       ^{:keys [id label]}
       [:li [:a (anchor-attrs (str "/#" id)) label]])]]
   [:div {:class "navbar-end gap-2"}
    (theme-toggle)
    [:a {:href "/auth/signin" :class "btn btn-ghost btn-sm hidden sm:inline-flex"} "Sign in"]
    [:a {:href "/auth/signup" :class "btn btn-primary btn-sm"} "Get started"]]])

(defn footer []
  [:footer {:class "footer bg-base-200 p-10 text-base-content sm:footer-horizontal"}
   [:nav {:class "grid grid-flow-col gap-8 md:gap-16"}
    (for [{:keys [heading links]} footer-columns]
      ^{:keys [heading links]}
      [:div
       [:h6 {:class "footer-title mb-2"} heading]
       [:ul {:class "space-y-1 text-sm opacity-80"}
        (for [{:keys [label href]} links]
          ^{:keys [label href]}
          [:li [:a (anchor-attrs href) label]])]])]
   [:aside
    [:p {:class "text-sm opacity-70"}
     (str site-name " - a subscription marketplace for any product.")]
    [:p {:class "text-sm opacity-70"} "© 2026 SubMarket. All rights reserved."]]])

(defn page
  "Renders a complete HTML document. Extra arguments are placed inside <main>.

  Options:
    :title       - document title, site name is used when absent
    :description - meta description, omitted when absent
    :theme       - \"dark\" to hard-code a theme, otherwise the visitor's
                   stored or system preference wins"
  [{:keys [title description theme]} & content]
  (htmx/page
   {:lang "en"}
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title (or title (str site-name " - subscriptions for anything"))]
    (when description
      [:meta {:name "description" :content description}])
    [:link {:href (css-href) :rel "stylesheet"}]
    [:script {:src "/js/htmx.min.js" :defer true}]
    (when theme
      [:script {:type "text/javascript"} (str "try{localStorage.setItem('submarket-theme','" theme "')}catch(e){}")])
    [:script {:type "text/javascript"} theme-script]]
   [:body {:class "flex min-h-screen flex-col bg-base-100 text-base-content"
           :hx-boost "true"}
    [:a {:href "#main" :class "sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded focus:bg-primary focus:px-4 focus:py-2 focus:text-primary-content"} "Skip to content"]
    (navbar)
    [:main {:id "main" :class "flex-1"}
     content]
    (footer)]))