(ns views.landing.content
  "All landing page copy and sample data.

  Kept separate from views.landing.sections so wording can be edited without
  touching any Hiccup. Sample data is placeholder content for the design, not
  real marketplace listings.")

(def hero
  {:eyebrow "Any product. Any business."
   :title "Turn anything you sell into a subscription"
   :body (str "SubMarket lets any business launch a recurring plan. A coffee shop can offer "
              "one coffee a day for €50 a month. A record shop can ship one vinyl a month. "
              "A gym can run a class every Tuesday. Your customers subscribe, you deliver.")
   :primary {:label "Browse example plans" :href "#plans"}
   :secondary {:label "Start selling" :href "/auth/signup"}})

(def stats
  [{:value "8" :label "categories open to any business"}
   {:value "Any" :label "cadence: weekly, monthly or quarterly"}
   {:value "0" :label "lock-in - pause or cancel any time"}])

(def trusted-by
  ["Morning Roast Co." "Vinyl & Co." "Boxed Weekly" "Trail Paws" "The Green Cart" "Studio Nine"])

(def categories
  [{:icon "☕" :name "Coffee & Tea" :blurb "A cup a day, or a bag a month."}
   {:icon "🥗" :name "Meal kits" :blurb "Planned dinners, delivered weekly."}
   {:icon "🐾" :name "Pet supplies" :blurb "Treats and toys on a schedule."}
   {:icon "🎧" :name "Vinyl & Books" :blurb "A new record or paperback monthly."}
   {:icon "🏋️" :name "Fitness classes" :blurb "Recurring sessions with a studio."}
   {:icon "🥬" :name "Fresh produce" :blurb "Seasonal boxes from local growers."}
   {:icon "🍫" :name "Snacks" :blurb "The monthly surprise box."}
   {:icon "🧹" :name "Household" :blurb "Refills instead of one-off orders."}])

(def plans
  [{:name "Daily Roast"
    :vendor "Morning Roast Co."
    :tagline "One coffee a day, roasted the night before."
    :price "€50"
    :unit "/month"
    :badge "Most popular"
    :featured true
    :features ["One freshly roasted coffee, delivered daily"
               "Rotating single-origin, never the same bag twice"
               "Pause, skip or cancel from your dashboard"
               "Free local delivery"]}
   {:name "Vinyl Club"
    :vendor "Vinyl & Co."
    :tagline "One record a month, chosen with you."
    :price "€29"
    :unit "/month"
    :badge nil
    :featured false
    :features ["One hand-picked record every month"
               "Genre preferences you set once"
               "Ships in recyclable packaging"
               "Skip any month"]}
   {:name "Weekly Box"
    :vendor "Boxed Weekly"
    :tagline "Seven dinners a week, sorted."
    :price "€89"
    :unit "/week"
    :badge nil
    :featured false
    :features ["Seven recipes with pre-portioned ingredients"
               "Change the plan size any week"
               "Pause before the next delivery"
               "Cancel in one click"]}])

(def how-it-works
  [{:title "A business lists a plan"
    :body "Describe what you sell, how often it arrives and what it costs. It can be anything - a product, a class, a box."}
   {:title "Subscribers pick a plan"
    :body "Browsers see your plan with the price and cadence up front, then subscribe in a couple of clicks."}
   {:title "You deliver on schedule"
    :body "The plan repeats automatically. Subscribers can pause, skip or cancel whenever they like."}])

(def for-business
  {:title "Sell on a schedule, not a one-off"
   :body (str "One-off sales end at the checkout. Subscriptions keep customers with you month after month, "
              "and SubMarket handles the repeat ordering so you get back to what you actually do.")
   :points [{:title "Set any cadence"
             :body "Weekly, monthly, quarterly - whatever suits the product."}
            {:title "Predictable revenue"
             :body "Know roughly what lands each month and plan your stock around it."}
            {:title "No lock-in for subscribers"
             :body "Easy to leave means people stay longer. Cancel rates stay low."}
            {:title "You stay in control"
             :body "Set your own price, your own delivery area and your own terms."}]
   :cta {:label "List your first plan" :href "/auth/signup"}})

(def pricing
  {:title "Simple pricing"
   :body "Subscribers browse for free. Businesses pay a small monthly fee, with no per-transaction commission."
   :tiers [{:name "Browse"
            :price "Free"
            :unit "forever"
            :audience "Subscribers"
            :features ["Unlimited browsing" "Subscribe to any plan" "Pause or cancel any time"]}
           {:name "Sell"
            :price "€19"
            :unit "/month per business"
            :audience "Businesses"
            :featured true
            :features ["Unlimited plans" "Subscriber management dashboard"
                       "Pause and skip controls" "Email support"]}
           {:name "Enterprise"
            :price "Talk to us"
            :unit nil
            :audience "Chains and franchises"
            :features ["Multiple locations" "Custom delivery areas"
                       "Dedicated account manager"]}]})

(def testimonial
  {:quote (str "We went from selling bags of coffee on Saturdays to a steady €4,000 a month "
               "of predictable deliveries. The subscribers plan themselves now.")
   :name "Elena Marchetti"
   :role "Owner, Morning Roast Co."})

(def faq
  [{:q "What can actually be sold as a subscription?"
    :a "Anything that can be delivered or provided repeatedly - physical goods, coffee, meals, classes, software, or a monthly box of anything. If you can ship it on a schedule, you can list it."}
   {:q "How often can a plan be delivered?"
    :a "Any cadence you like: weekly, every two weeks, monthly or quarterly. You set the schedule, and subscribers can pause or skip whenever they want."}
   {:q "Can I run a subscription for my small shop?"
    :a "Yes. SubMarket is built for independent businesses as much as larger ones. Many sellers are single-person operations, and the pricing is the same."}
   {:q "What does it cost to sell on SubMarket?"
    :a "€19 a month per business, with unlimited plans and no per-transaction commission. There is nothing to pay to browse or subscribe."}
   {:q "Can subscribers cancel easily?"
    :a "Yes, and that is deliberate. Subscribers can pause, skip or cancel from their own dashboard without emailing anyone, which keeps churn low for sellers."}
   {:q "Do I need to handle payments and delivery myself?"
    :a "Payments are handled through the platform. Delivery stays yours for local businesses, or you can ship nationally."}])

(def final-cta
  {:title "Ready to sell by subscription?"
   :body "Create an account and list your first plan today. Browsing is free and always will be."})