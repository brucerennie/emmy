#_"SPDX-License-Identifier: GPL-3.0"

(ns emmy.pattern.match
  "Implementation of a emmy.pattern.matching system inspired by [Gerald Jay Sussman's
  lecture notes for MIT
  6.945](http://groups.csail.mit.edu/mac/users/gjs/6.945/).
  See [[emmy.pattern.rule]] for a higher-level API.

  [[emmy.pattern.match]] and [[emmy.pattern.rule]] are spiritually similar to Alexey
  Radul's [Rules](https://github.com/axch/rules) library for Scheme, and the
  emmy.pattern.matching system described in GJS and Hanson's [Software Design for
  Flexibility](https://mitpress.mit.edu/books/software-design-flexibility)."
  (:refer-clojure :exclude [sequence or and not])
  (:require [clojure.core :as core]
            [emmy.pattern.syntax :as s]))

;; # Emmy.Pattern.Matching
;;
;; The library is built out of a few stacked ideas: matcher combinators,
;; matchers, consequences and rules.
;;
;; ### Matcher Combinators
;;
;; - A "matcher combinator" is a function that takes three arguments:
;;
;; `[binding-frame data success-continuation]`
;;
;;  and either returns `nil` or `false`, or calls its `success-continuation`
;;  with a potentially-updated binding map.
;;
;;
;; ### Matcher
;;
;; A `matcher` is a function of a `data` input that can succeed or fail. If it
;; succeeds, it will return the binding map generated by the matching process.
;;
;; On failure, a matcher returns a special `failure` singleton object. You can
;; check for this object using [[fail?]].
;;
;; ### Consequence Functions
;;
;; A consequence is a function from the binding map returned by a matcher to a
;; final form. A consequence can fail by returning `false`, `nil` or the special
;; `failure` singleton.
;;
;; ### Rule
;;
;; A rule is a function of `data` built from a pair of:
;;
;; - a matcher
;; - a consequence function.
;;
;; If the matcher fails on the rule's input, the whole rule returns `failure`.
;; If the match succeeds, the rule calls the consequence function with the
;; matcher's binding map. If THIS function succeeds, the rule returns that
;; value.
;;
;; If the consequence function returns `nil` or `false` (or `failure`), the
;; whole `rule` fails with `failure`.
;;
;; ## Combinators

;; `emmy.pattern.match` contains many matcher combinators. These are either functions
;; with the contract described above, or functions that take one or more
;; combinators and return a new combinator with the same contract. Examples
;; are [[or]], [[not]], [[and]], [[match-when]] and more below.
;;
;; `emmy.pattern.rule` contains many rule combinators, which are either primitive
;; rules or functions from zero or more rules to a new rule.
;;
;; Finally, `emmy.pattern.syntax` defines a small pattern language. Any matcher
;; combinator that take another matcher can take a pattern instead.
;;
;;
;; ### Basic Matcher Combinators

(defn fail
  "Matcher which will fail for any input."
  [_ _ _])

(defn pass
  "Matcher that succeeds (with no new bindings) for any input, passing along its
  input frame."
  [frame _ succeed]
  (succeed frame))

(defn with-frame
  "Takes a `new-frame` of bindings and returns a matcher that will ignore its
  input and always succeed by replacing the current map of bindings with
  `new-frame`."
  [new-frame]
  (fn [_ _ succeed]
    (succeed new-frame)))

(defn update-frame
  "Takes a function from `frame` to a new frame (or false) and any number of
  arguments `args`, and returns a matcher that will ignore its input and

  - succeed with `(apply f frame args)` if that value is truthy,
  - fail otherwise."
  [f & args]
  (fn [frame _ succeed]
    (when-let [new-frame (apply f frame args)]
      (succeed new-frame))))

(defn predicate
  "Takes a predicate function `pred` and returns a matcher that succeeds (with no
  new bindings) if its data input passes the predicate, fails otherwise."
  [pred]
  (fn predicate-match [frame data succeed]
    (core/and (pred data)
              (succeed frame))))

(defn frame-predicate
  "Takes a predicate function `pred` and returns a matcher that succeeds (with no
  new bindings) if its data input passes the predicate, fails otherwise."
  [pred]
  (fn frame-pred [frame _ succeed]
    (core/and (pred frame)
              (succeed frame))))

(defn eq
  "Takes some input `x` and returns a matcher which succeeds if its data input is
  equal to `x` (via `=` or the optional `eq-fn` argument). Fails otherwise.

  The frame is not modified."
  ([x] (eq x =))
  ([x eq-fn]
   (predicate
    (fn [other]
      (eq-fn x other)))))

(defn bind
  "Takes a binding variable `sym` and an optional predicate `pred`, and returns a
  matcher that binds its input to `sym` in the returned `frame`.

  The returned matcher only succeeds if `input` passes `pred`.

  If `sym` is already present in `frame`, the matcher only succeeds if the
  values are equal, fails otherwise.

  NOTE: If `sym` is the wildcard `_`, the returned matcher will not introduce a
  new binding, but _will_ still check the predicate."
  ([sym]
   (bind sym (fn [_] true)))
  ([sym pred]
   (if (s/wildcard? sym)
     (predicate pred)
     (fn bind-match [frame data succeed]
       (when (pred data)
         (if-let [[_ binding] (find frame sym)]
           (core/and (= binding data)
                     (succeed frame))
           (succeed (assoc frame sym data))))))))

;; ### Matcher Combinators
;;
;; This section introduces functions that are able to build new matcher
;; combinators out of the primitive matcher combinators defined above.
;;
;; Each of the following functions can take EITHER a matcher combinator or
;; a "pattern". The emmy.pattern.syntax is described in `emmy.pattern.syntax`.
;;
;; As an example, you might provide the symbol `'?x` instead of an
;; explicit `(bind '?x)`:

(comment
  (let [m (match-if odd? '?odd '?even)]
    (= [{'?odd 11} {'?even 12}]
       [(m {} 11 identity)
        (m {} 12 identity)])))

(declare pattern->combinators)

(defn match-when
  "Returns a matcher that passes its `frame` on to `success-pattern` if `pred`
  succeeds on its data input, fails otherwise."
  [pred success-pattern]
  (let [match (pattern->combinators success-pattern)]
    (fn [frame xs success]
      (when (pred xs)
        (match frame xs success)))))

(defn match-if
  "Returns a matcher that passes its `frame` on to `success-pattern` if `pred`
  succeeds on its data input, `fail-pattern` otherwise.

  If no `fail-matcher` is supplied, the behavior is equivalent
  to [[match-when]]."
  ([pred success-pattern]
   (match-when pred success-pattern))
  ([pred success-pattern fail-pattern]
   (let [s-match (pattern->combinators success-pattern)
         f-match (pattern->combinators fail-pattern)]
     (fn [frame xs success]
       (if (pred xs)
         (s-match frame xs success)
         (f-match frame xs success))))))

(defn or
  "Takes a sequence of patterns, and returns a matcher that will apply its
  arguments to each matcher in turn. Returns the value of the first pattern that
  succeeds."
  ([] fail)
  ([pattern] (pattern->combinators pattern))
  ([pattern & more]
   (let [matchers (map pattern->combinators (cons pattern more))]
     (fn call [frame xs succeed]
       (some #(% frame xs succeed)
             matchers)))))

(defn and
  "Takes a sequence of patterns and returns a matcher that will apply its
  arguments to the first pattern;

  If that match succeeds, the next pattern will be called with the new, returned
  frame (and the original data and success continuation).

  The returned matcher succeeds only of all patterns succeed, and returns the
  value of the final pattern."
  ([] pass)
  ([pattern] (pattern->combinators pattern))
  ([pattern & more]
   (let [matchers (map pattern->combinators (cons pattern more))]
     (fn [frame xs succeed]
       (reduce (fn [acc matcher]
                 (if acc
                   (matcher acc xs succeed)
                   (reduced acc)))
               frame
               matchers)))))

(defn not
  "Takes a `pattern` and returns a matcher that will apply its arguments to the
  `pattern`. The returned pattern will succeed with the original frame if
  `pattern` fails, and fail if `pattern` succeeds."
  [pattern]
  (let [match (pattern->combinators pattern)]
    (fn [frame xs succeed]
      (when-not (match frame xs succeed)
        (succeed frame)))))

;; ### Lists and Segments
;;
;; Segment variables introduce some additional trouble. Unlike other matchers, a
;; segment variable is not tested against a fixed input, but against a sequence
;; such that it may match any prefix. This means that in general, segment
;; variables must search, trying one match and possibly backtracking.
;;
;; There are two circumstances when the search can be avoided:
;;
;; - if the variable is already bound, the bound value needs to be checked
;;   against the input data, and will either fail or succeed.
;;
;; - If the segment variable is the last matcher in its enclosing list (which
;;   actually happens quite often!) then the segment matcher can match the
;;   entire remaining segment, no search required.
;;
;; This requires a different interface for the continutation. Segment matchers
;; pass TWO arguments into their success continuation - the binding frame, and
;; the remaining unmatched segment.
;;
;; The following two functions let us mark matcher combinators with this
;; interface using their metadata.

(defn as-segment-matcher
  "Takes a matcher and returns `f` with its metadata modified such
  that [[segment-matcher?]] will return `true` when applied to `f`."
  [f]
  (vary-meta f assoc ::segment? true))

(defn- segment-matcher?
  "Returns true if the supplied matcher `f` is a segment matcher, false
  otherwise."
  [f]
  (::segment? (meta f) false))

(defn segment
  "Takes a binding variable `sym` and returns a matcher that calls its success
  continuation with successively longer prefixes of its (sequential) data input
  bound to `sym` inside the frame.

  If `sym` is already present in the frame, the returned matcher only succeeds
  if the bound value is a prefix of the data argument `xs`.

  If `sym` matches the wildcard symbol `_`, the behavior is the same, but no new
  binding is introduced.

  NOTE: the returned matcher will call its success continuation with TWO
  arguments; the new frame and the remaining elements in `xs`. This is a
  different contract than all other matchers, making `segment` appropriate for
  use inside `sequence`."
  ([sym]
   (segment sym (constantly true)))
  ([sym pred]
   (as-segment-matcher
    (fn segment-match [frame xs succeed]
      (let [xs (core/or xs [])]
        (when (sequential? xs)
          (if-let [binding (core/and
                            (core/not (s/wildcard? sym))
                            (frame sym))]
            (when (pred binding)
              (let [binding-count (count binding)]
                (when (= (take binding-count xs) binding)
                  (succeed frame (drop binding-count xs)))))
            (loop [prefix []
                   suffix xs]
              (core/or
               (core/and (pred prefix)
                         (let [new-frame (if (s/wildcard? sym)
                                           frame
                                           (assoc frame sym prefix))]
                           (succeed new-frame suffix)))
               (core/and (seq suffix)
                         (recur (conj prefix (first suffix))
                                (next suffix))))))))))))

(defn- entire-segment
  "Similar to [[segment]], but matches the entire remaining sequential argument
  `xs`. Fails if its input is not sequential, or `sym` is already bound to some
  other variable or non-equal sequence.

  If `sym` matches the wildcard symbol `_`, succeeds if `xs` is a sequence and
  introduces NO new bindings.

  Calls its continuation with the new frame and `nil`, always."
  ([sym]
   (entire-segment sym (constantly true)))
  ([sym pred]
   (as-segment-matcher
    (fn entire-segment-match [frame xs succeed]
      (let [xs (core/or xs [])]
        (when (core/and (sequential? xs) (pred xs))
          (if (s/wildcard? sym)
            (succeed frame nil)
            (if-let [binding (frame sym)]
              (when (= xs binding)
                (succeed frame nil))
              (succeed (assoc frame sym xs) nil)))))))))

(defn reverse-segment
  "Returns a matcher that takes a binding variable `sym`, and succeeds if it's
  called with a sequential data argument with a prefix that is the REVERSE of
  the sequence bound to `sym` in `frame`.

  Fails if any of the following are true:

  - `sym` is not bound in the frame
  - `sym` is bound to something other than a vector prefix created by `segment`
  - the data argument does not have a prefix matching the reverse of vector
    bound to `sym`."
  ([sym]
   (reverse-segment sym (constantly true)))
  ([sym pred]
   (as-segment-matcher
    (fn reverse-segment-match [frame xs succeed]
      (let [xs (core/or xs [])]
        (when (sequential? xs)
          (when-let [binding (frame sym)]
            (when (vector? binding)
              (let [binding-count (count binding)
                    reversed      (rseq binding)]
                (when (core/and (= (take binding-count xs) reversed)
                                (pred xs))
                  (succeed frame (drop binding-count xs))))))))))))

(defn sequence*
  "Version of [[sequence]] that takes an explicit sequence of `patterns`, vs the
  multi-arity version. See [[sequence]] for documentation."
  [patterns]
  (fn sequence-match [frame xs succeed]
    (when (sequential? xs)
      (letfn [(step [frame items matchers]
                (letfn [(try-elem [matcher]
                          (matcher frame
                                   (first items)
                                   (fn [new-frame]
                                     (step new-frame
                                           (next items)
                                           (next matchers)))))

                        (try-segment [matcher]
                          (matcher frame
                                   items
                                   (fn [new-frame new-xs]
                                     (step new-frame
                                           new-xs
                                           (next matchers)))))]
                  (cond matchers (let [m (first matchers)]
                                   (if (segment-matcher? m)
                                     (try-segment m)
                                     (core/and (seq items)
                                               (try-elem m))))

                        (seq items) false
                        :else (succeed frame))))]
        (let [matchers (map pattern->combinators patterns)]
          (step frame xs matchers))))))

(defn sequence
  "Takes a sequence of patterns and returns a matcher that accepts a sequential
  data input, and attempts to match successive items (or segments) in the
  sequence with the supplied patterns.

  The returned matcher succeeds if `patterns` can consume all elements, fails
  otherwise (or of any of the supplied patterns fails on its argument).

  On success, the returned matcher calls its success continuation with a frame
  processed by each pattern in sequence."
  [& patterns]
  (sequence* patterns))

;; ## Emmy.Pattern.Matching Compiler
;;
;; The next function transforms a pattern (as defined by `emmy.pattern.syntax`) into
;; a matcher combinator. Any function you pass to [[pattern->combinators]] is
;; returned, so it's appropriate to pass other matcher combinators as pattern
;; elements.

(defn pattern->combinators
  "Given a pattern (built using the syntax elements described in
  `emmy.pattern.syntax`), returns a matcher combinator that will successfully
  match data structures described by the input pattern, and fail otherwise."
  [pattern]
  (cond (fn? pattern) pattern

        (s/binding? pattern)
        (bind (s/variable-name pattern)
              (s/restriction pattern))

        (s/segment? pattern)
        (segment
         (s/variable-name pattern)
         (s/restriction pattern))

        (s/reverse-segment? pattern)
        (reverse-segment
         (s/reverse-segment-name pattern)
         (s/restriction pattern))

        (s/wildcard? pattern) pass

        (core/or (seq? pattern)
                 (vector? pattern))
        (if (empty? pattern)
          (eq pattern)
          (sequence*
           (concat (map pattern->combinators (butlast pattern))
                   (let [p (last pattern)]
                     [(if (s/segment? p)
                        (entire-segment
                         (s/variable-name p)
                         (s/restriction p))
                        (pattern->combinators p))]))))

        :else (eq pattern)))

;; This concludes the matcher combinator section of our program. On to the next
;; act: the "matcher"!
;;
;;
;; ## Top Level Matchers
;;
;; Once you've built up a combinator out of smaller matcher combinators, you can
;; turn your combinator into a "matcher". This is a function from a data object
;; to either:
;;
;; - the binding map, if successful
;; - if failed, a special `failure` singleton object.
;;
;; This interface will become important in `emmy.rule`, for building up
;; groups of rules that can, say, search for the first successful matcher of
;; many, or accumulate binding maps from matchers run in sequence until one
;; fails.
;;
;; The next few functions define this explicit `failure` singleton.

(defrecord Failure [])

(def ^{:doc "Singleton object representing the failure of a matcher to match its
  input. Check for failure with [[failed?]]"}
  failure
  (Failure.))

(defn failed?
  "Returns true if `x` is equivalent to the failure sentinel [[failure]], false
  otherwise."
  [x]
  (instance? Failure x))

(defn matcher
  "Takes a `pattern` or matcher combinator, and returns a function from a data
  object to either:

  - A successful map of bindings extracted by matching the supplied `pattern` or
    combinator to the input data
  - An explicit `failure` object

  Check for failure with [[failed?]].

  Optionally, you can supply a predicate `pred`. `pred` takes the map of
  bindings from a successful match and returns either:

  - `nil`, `false` or the explicit `failure` object to force a match failure,
    potentially causing a backtrack back into the data
  - a map of NEW bindings to merge into the binding map (and signal success)

  Any other truthy value signals success with no new bindings."
  ([pattern]
   (let [match (pattern->combinators pattern)]
     (fn [data]
       (core/or (match {} data identity)
                failure))))
  ([pattern pred]
   (let [match (pattern->combinators pattern)
         success (fn [frame]
                   (when-let [m (pred frame)]
                     (when (core/and m (not (failed? m)))
                       (if (map? m)
                         (merge frame m)
                         frame))))]
     (fn [data]
       (core/or (match {} data success)
                failure)))))

(defn match
  "Convenience function that creates a matcher from the supplied `pattern` (and
  optional predicate `pred`) and immediately applies it to `data`.

  Equivalent to:

  ```clojure
  ((matcher pattern pred) data)
  ```"
  ([pattern data]
   ((matcher pattern) data))
  ([pattern pred data]
   ((matcher pattern pred) data)))

(defn foreach-matcher
  "Takes a `pattern` and side-effecting callback function `f`, and returns a
  matcher that calls `f` with a map of bindings for every possible match of
  `pattern` to its input data.

  For a convenience function that applies the matcher to data immediately,
  see [[foreach]].

  NOTE: If you pass a segment matcher, `f` must accept two arguments - the
  binding map, and the sequence of all remaining items that the segment
  matcher rejected."
  [pattern f]
  (let [match (pattern->combinators pattern)
        cont (fn ([frame]
                  (f frame)
                  false)
               ([frame xs]
                (f frame xs)
                false))]
    (fn [data]
      (match {} data cont))))

(defn foreach
  "Convenience function that creates a [[foreach-matcher]] from the supplied
  `pattern` and callback `f` and immediately applies it to `data`.

  Equivalent to:

  ```clojure
  ((foreach-matcher pattern pred) data)
  ```"
  [pattern f data]
  ((foreach-matcher pattern f) data))

(defn all-results-matcher
  "Takes a `pattern` and callback function `f`, and returns a matcher that takes a
  `data` argument and returns a sequence of every possible match of `pattern` to
  the data.

  For a convenience function that applies the matcher to data immediately,
  see [[all-results]].

  NOTE: If you pass a segment matcher, `f` must accept two arguments - the
  binding map, and the sequence of all remaining items that the segment
  matcher rejected."
  [pattern]
  (let [match (pattern->combinators pattern)]
    (fn [data]
      (let [results (atom [])
            cont (fn
                   ([frame]
                    (swap! results conj frame)
                    false)
                   ([frame xs]
                    (swap! results conj [frame xs])
                    false))]
        (match {} data cont)
        @results))))

(defn all-results
  "Convenience function that creates an [[all-results-matcher]] from the supplied
  `pattern` and immediately applies it to `data`.

  Equivalent to:

  ```clojure
  ((all-results-matcher pattern pred) data)
  ```"
  [pattern data]
  ((all-results-matcher pattern) data))