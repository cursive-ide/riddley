(ns cursive.riddley
  (:refer-clojure :exclude [macroexpand])
  (:require [cursive.riddley.compiler :as cmp])
  (:import (clojure.lang IObj MapEntry IRecord)
           (java.util Map$Entry)))

(defn- walkable? [x]
  (and
    (sequential? x)
    (not (vector? x))
    (not (instance? Map$Entry x))))

(defn macroexpand
  "Expands both macros and inline functions. Honors local bindings."
  [x]
  (cmp/with-base-env
    (if (seq? x)
      (let [frst (first x)]

        (if (contains? (cmp/locals) frst)

          ;; might look like a macro, but for our purposes it isn't
          x

          (let [x' (macroexpand-1 x)]
            (if-not (identical? x x')
              (macroexpand x')

              ;; if we can't macroexpand any further, check if it's an inlined function
              (if-let [inline-fn (and (seq? x')
                                   (symbol? (first x'))
                                   (-> x' meta ::transformed not)
                                   (or
                                     (-> x' first resolve meta :inline-arities not)
                                     ((-> x' first resolve meta :inline-arities)
                                       (count (rest x'))))
                                   (-> x' first resolve meta :inline))]
                (let [x'' (apply inline-fn (rest x'))]
                  (macroexpand
                    ;; unfortunately, static function calls can look a lot like what we just
                    ;; expanded, so prevent infinite expansion
                    (if (= '. (first x''))
                      (with-meta
                        (concat (butlast x'')
                          [(if (instance? IObj (last x''))
                             (with-meta (last x'')
                               (merge
                                 (meta (last x''))
                                 {::transformed true}))
                             (last x''))])
                        (meta x''))
                      x'')))
                x')))))
      x)))

;;;

(defn- do-handler [f [_ & body]]
  (list* 'do
    (doall
      (map f body))))

(defn- fn-handler [f x]
  (let [prelude (take-while (complement sequential?) x)
        remainder (drop (count prelude) x)
        remainder (if (vector? (first remainder))
                    (list remainder) remainder)
        body-handler (fn [x]
                       (cmp/with-lexical-scoping
                         (doseq [arg (first x)]
                           (cmp/register-arg arg))
                         (doall
                           (list* (first x)
                             (map f (rest x))))))]

    (cmp/with-lexical-scoping

      ;; register a local for the function, if it's named
      (when-let [nm (second prelude)]
        (cmp/register-local nm
          (list* 'fn* nm
            (map #(take 1 %) remainder))))

      (concat
        prelude
        (if (seq? (first remainder))
          (doall (map body-handler remainder))
          [(body-handler remainder)])))))

(defn- def-handler [f x]
  (let [[_ n & r] x]
    (cmp/with-lexical-scoping
      (cmp/register-local n '())
      (list* 'def (f n) (doall (map f r))))))

(defn- let-bindings [f x]
  (->> x
    (partition-all 2)
    (mapcat
      (fn [[k v]]
        (let [[k v] [k (f v)]]
          (cmp/register-local k v)
          [k v])))
    vec))

(defn- reify-handler [f x]
  (let [[_ classes & fns] x]
    (list* 'reify* classes
      (doall
        (map
          (fn [[nm args & body]]
            (cmp/with-lexical-scoping
              (doseq [arg args]
                (cmp/register-arg arg))
              (list* nm args (doall (map f body)))))
          fns)))))

(defn- deftype-handler [f x]
  (let [[_ type resolved-type args _ interfaces & fns] x]
    (cmp/with-lexical-scoping
      (doseq [arg args]
        (cmp/register-arg arg))
      (list* 'deftype* type resolved-type args :implements interfaces
        (doall
          (map
            (fn [[nm args & body]]
              (cmp/with-lexical-scoping
                (doseq [arg args]
                  (cmp/register-arg arg))
                (list* nm args (doall (map f body)))))
            fns))))))

(defn- let-handler [f x]
  (cmp/with-lexical-scoping
    (doall
      (list*
        (first x)
        (let-bindings f (second x))
        (map f (drop 2 x))))))

(defn- case-handler [f x]
  (let [prefix (butlast (take-while (complement map?) x))
        default (last (take-while (complement map?) x))
        body (first (drop-while (complement map?) x))
        suffix (rest (drop-while (complement map?) x))]
    (concat
      prefix
      [(f default)]
      [(let [m (->> body
                 (map
                   (fn [[k [idx form]]]
                     [k [idx (f form)]]))
                 (into {}))]
         (if (every? number? (keys m))
           (into (sorted-map) m)
           m))]
      suffix)))

(defn- catch-handler [f x]
  (let [[_ type var & body] x]
    (cmp/with-lexical-scoping
      (when var
        (cmp/register-arg (with-meta var (merge (meta var) {:tag type}))))
      (list* 'catch type var
        (doall (map f body))))))

(defn- dot-handler [f x]
  (let [[_ hostexpr mem-or-meth & remainder] x]
    (list* '.
      (f hostexpr)
      (if (walkable? mem-or-meth)
        (list* (first mem-or-meth)
          (doall (map f (rest mem-or-meth))))
        (f mem-or-meth))
      (doall (map f remainder)))))

(defn contains-tag?
  [form]
  (or (-> form meta ::expand-to)
    (if (coll? form) (some contains-tag? form))))

(defn macroexpand-all
  "Recursively macroexpands all forms, preserving the &env special variables."
  [form]
  (cmp/with-base-env
    (let [form (if (instance? IObj form)
                 (vary-meta form dissoc ::expanded)
                 form)
          x (if (contains-tag? form)
              (try
                (macroexpand form)
                (catch ClassNotFoundException _
                  form))
              form)
          x (if-not (identical? x form)
              (vary-meta x assoc ::expanded true)
              x)
          x (if (instance? IObj x)
              (with-meta x (dissoc (merge (select-keys (meta form) [::top-form])
                                     (meta x))
                             ::expand-to))
              x)
          x' (cond

               (and (walkable? x) (= 'quote (first x)))
               x

               (walkable? x)
               ((condp = (first x)
                  'do do-handler
                  'def def-handler
                  'fn* fn-handler
                  'let* let-handler
                  'loop* let-handler
                  'letfn* let-handler
                  'case* case-handler
                  'catch catch-handler
                  'reify* reify-handler
                  'deftype* deftype-handler
                  '. dot-handler
                  #(doall (map %1 %2)))
                 macroexpand-all x)

               (instance? Map$Entry x)
               (MapEntry.
                 (macroexpand-all (key x))
                 (macroexpand-all (val x)))

               (vector? x)
               (vec (map macroexpand-all x))

               (instance? IRecord x)
               x

               (map? x)
               (into {} (map macroexpand-all x))

               (set? x)
               (set (map macroexpand-all x))

               ;; special case to handle clojure.test
               (and (symbol? x) (-> x meta :test))
               (vary-meta x update-in [:test] macroexpand-all)

               :else
               x)]
      (if (instance? IObj x')
        (with-meta x' (merge (meta x) (meta x')))
        x'))))
