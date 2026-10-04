(ns views.support
  "Helpers for asserting on views.

  The chrome and section functions return hiccup vectors rather than HTML
  strings, so structure can be asserted by walking the tree directly instead of
  rendering to a string and parsing it back apart. Assertions about a whole
  rendered page still use the string, via classes-in, hrefs and ids-in."
  (:require
   [clojure.string :as str]))

(defn- descend
  "Children of a node, descending only into vectors and seqs.

  Attribute maps must not be descended into: hiccup stores attributes in a map,
  and a map entry is itself a vector, so walking into maps turns [\"class\" \"btn\"]
  into something indistinguishable from a real element."
  [node]
  (cond
    (vector? node) (rest node)
    (sequential? node) node
    :else nil))

(defn nodes
  "Every node reachable from tree, without descending into attribute maps."
  [tree]
  (tree-seq (complement map?) descend tree))

(defn tag
  "Tag of a hiccup element, nil for anything else.

  Hiccup tags are keywords, not symbols."
  [node]
  (when (and (vector? node) (keyword? (first node)))
    (first node)))

(defn attrs
  "Attribute map of a hiccup element, nil for anything else."
  [node]
  (when (and (vector? node) (map? (second node)))
    (second node)))

(defn classes
  "Class names on a hiccup element, as a set."
  [node]
  (if-let [c (:class (attrs node))]
    (into #{} (remove str/blank?) (str/split (str c) #"\s+"))
    #{}))

(defn has-class?
  "True when node carries all of cs."
  [node & cs]
  (every? (classes node) cs))

(defn children
  "Nested elements of a hiccup node."
  [node]
  (filterv tag (descend node)))

(defn elements
  "Every hiccup element in tree, including tree itself."
  [tree]
  (filterv tag (nodes tree)))

(defn find-by-class
  "First element in tree carrying all of cs."
  [tree & cs]
  (first (filter #(apply has-class? % cs) (elements tree))))

(defn text
  "All text content in tree, concatenated.

  Attribute values are excluded, so class names do not show up as text."
  [tree]
  (apply str (filter string? (nodes tree))))

(defn within?
  "True when an element carrying outer-class contains one carrying inner-class.

  The way to assert containment without tracking parent links."
  [tree outer-class inner-class]
  (boolean
   (some (fn [outer]
           (some #(has-class? % inner-class) (elements outer)))
         (filter #(has-class? % outer-class) (elements tree)))))

(defn classes-in
  "Every class name used anywhere in a rendered HTML string."
  [body]
  (into #{}
        (mapcat #(remove str/blank? (str/split (second %) #"\s+")))
        (re-seq #"class=\"([^\"]+)\"" body)))

(defn hrefs
  "Every anchor href in a rendered HTML string, in document order.

  Anchors only: a bare href=\"...\" also matches the stylesheet link, which is
  not a navigable route."
  [body]
  (mapv second (re-seq #"<a\b[^>]*\shref=\"([^\"]*)\"" body)))

(defn ids-in
  "Every id attribute in a rendered HTML string."
  [body]
  (into #{} (map second) (re-seq #"(?:^|\s)id=\"([^\"]+)\"" body)))

(defn css-selector
  "The selector Tailwind emits for a class name, with CSS identifier escaping.

  Dots, colons, slashes, parens and brackets are all escaped in the generated
  stylesheet, so a naive substring check gives false negatives for anything
  like lg:grid-cols-3, text-base-content/70 or min-h-[calc(100vh-8rem)]."
  [c]
  (str "."
       (apply str
              (map (fn [ch]
                     (if (contains? #{\. \: \/ \% \( \) \[ \]} ch)
                       (str "\\" ch)
                       ch))
                   (str c)))))