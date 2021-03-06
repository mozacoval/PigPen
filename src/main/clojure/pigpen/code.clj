;;
;;
;;  Copyright 2013 Netflix, Inc.
;;
;;     Licensed under the Apache License, Version 2.0 (the "License");
;;     you may not use this file except in compliance with the License.
;;     You may obtain a copy of the License at
;;
;;         http://www.apache.org/licenses/LICENSE-2.0
;;
;;     Unless required by applicable law or agreed to in writing, software
;;     distributed under the License is distributed on an "AS IS" BASIS,
;;     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;;     See the License for the specific language governing permissions and
;;     limitations under the License.
;;
;;

(ns pigpen.code
  "Contains functions that assist in handling user code in operations like map
or reduce."
  (:require [pigpen.raw :as raw])
  (:import [org.apache.pig.data DataBag]
           [java.lang.reflect Method]))

(set! *warn-on-reflection* true)

(defn object-projection [alias args f]
  (raw/projection-flat$ alias
    (raw/code$ DataBag args
      (raw/expr$ `(require '[pigpen.pig])
                 `(pigpen.pig/exec-multi :frozen :native [(pigpen.pig/map->bind ~f)])))))

(defn arity
  "Returns the arities of the invoke methods for f.
   Also returns the minimum varargs arity"
  [f]
  {:pre [f]}
  (let [methods (-> f class .getDeclaredMethods)
        fixed (->> methods
                (filter (fn [^Method m] (= "invoke" (.getName m))))
                (map (fn [^Method m] (-> m .getParameterTypes alength)))
                set)
        varargs (->> methods
                  (filter (fn [^Method m] (= "doInvoke" (.getName m))))
                  (map (fn [^Method m] (-> m .getParameterTypes alength)))
                  first)]
    [fixed varargs]))

(defn format-arity [fixed varargs]
  (let [symbols (->> (range 97 123)
                  (map (comp symbol str char)))
        fixed (for [a fixed]
                (vec (take a symbols)))
        varargs (if varargs
                  [(vec
                     (concat
                       (vec (take (- varargs 1) symbols))
                       '[& more]))])]
    (concat fixed varargs)))

(defn assert-arity [f n]
  {:pre [f (integer? n) (<= 0 n)]}
  (let [f' (eval f)
        [fixed varargs] (arity f')]
    (assert
      (or (fixed n) (if varargs (<= varargs n)))
      (str "Expecting arity: " n " Found arities: "
           (pr-str (format-arity fixed varargs))))))

(defn ^:private make-binding [k v]
  (let [{:keys [local]} (meta k)
        {:keys [pig]} (meta v)]
    (cond
      (fn? v) nil
      pig nil
      local nil
      :else [k `(quote ~v)])))

(defn trap-locals* [keys values f]
  (let [args (vec (mapcat make-binding keys values))]
    (if (empty? args)
      f
      `(let ~args ~f))))

(defmacro trap-locals [f]
  (let [keys# (vec (keys &env))]
    `(trap-locals* '~keys# ~keys# '~f)))
