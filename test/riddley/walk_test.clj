(ns riddley.walk-test
  (:require
    [clojure.test :refer :all]
    [cursive.riddley :as r]))

(deftest catch-old-fn*-syntax
  (is (= (r/macroexpand-all '(fn* tst [x ^::r/expand-to seq]))
         '(fn* tst ([x seq])))))

(deftest dot-expansion
  (is (= (r/macroexpand-all '(^::r/expand-to bit-and 2 1))
         '(. clojure.lang.Numbers (and 2 1)))))

(deftest do-not-macroexpand-quoted-things
  (is (= '(def p '(fn []))
        (r/macroexpand-all '(def p '(fn []))))))

(deftest handle-def-with-docstring
  (is (= '(def x "docstring" (. clojure.lang.Numbers (add 1 2)))
         (r/macroexpand-all '(def x "docstring" (^::r/expand-to + 1 2))))))

(deftest walk-over-instance-expression-in-dot-forms
  (is (= '(. (. clojure.lang.Numbers (add 1 2)) toString)
         (r/macroexpand-all '(.toString (^::r/expand-to + 1 2))))))
