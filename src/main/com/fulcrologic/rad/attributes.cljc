(ns ^:always-reload com.fulcrologic.rad.attributes
  #?(:cljs (:require-macros com.fulcrologic.rad.attributes))
  (:require
    [clojure.spec.alpha :as s]
    [clojure.walk :as walk]
    [taoensso.timbre :as log]
    [com.fulcrologic.guardrails.core :refer [>defn => >def >fdef ?]]
    [com.fulcrologic.rad.ids :refer [new-uuid]])
  #?(:clj
     (:import (clojure.lang IFn)
              (javax.crypto.spec PBEKeySpec)
              (javax.crypto SecretKeyFactory)
              (java.util Base64))))

(>def ::qualified-key qualified-keyword?)
(>def ::type keyword?)
(>def ::target qualified-keyword?)
(>def ::attribute (s/keys :req [::type ::qualified-key]
                    :opt [::target]))
(>def ::attributes (s/every ::attribute))

(declare map->Attribute)

(>defn new-attribute
  "Create a new attribute, which is represented as an Attribute record.

  NOTE: attributes are usable as functions which act like their qualified keyword. This allows code-navigable
  use of attributes throughout the system...e.g (account/id props) is like (::account/id props), but will
  be understood by an IDE's jump-to feature when you want to analyze what account/id is.  Use `defattr` to
  populate this into a symbol.

  Type can be one of :string, :int, :uuid, etc. (more types are added over time,
  so see main documentation and your database adapter for more information).

  The remaining argument is an open map of additional things that any subsystem can
  use to describe facets of this attribute that are important to your system.

  If `:ref` is used as the type then the ultimate ID of the target entity should be listed in `m`
  under the ::target key.
  "
  [kw type m]
  [qualified-keyword? keyword? map? => ::attribute]
  (do
    (when (and (= :ref type) (not (contains? m ::target)))
      (log/warn "Reference attribute" kw "does not list a target ID. Resolver generation will not be accurate."))
    (map->Attribute
      (-> m
        (assoc ::type type)
        (assoc ::qualified-key kw)))))

#?(:clj
   (defrecord Attribute []
     IFn
     (invoke [this m] (get m (::qualified-key this))))
   :cljs
   (defrecord Attribute []
     IFn
     (-invoke [this m] (get m (::qualified-key this)))))

#?(:clj
   (defmacro defattr
     "Define a new attribute into a sym. Equivalent to (def sym (new-attribute k type m))."
     [sym k type m]
     `(def ~sym (new-attribute ~k ~type ~m))))

(def attribute-registry (atom {}))

(defn register-attributes!
  "Resets the attribute registry to include only the given attributes.
   Should be called early in the startup of the client and server."
  [attributes]
  (swap! attribute-registry
    (fn [r]
      (reduce
        (fn [reg {::keys [qualified-key] :as a}]
          (assoc reg qualified-key a))
        {}
        attributes))))

(>defn key->attribute
  "Look up a schema attribute using the runtime registry. Avoids having attributes in application state"
  [k]
  [::qualified-key => (? ::attribute)]
  (get @attribute-registry k))

(>defn to-many?
  "Returns true if the attribute with the given key is a to-many."
  [k]
  [keyword? => boolean?]
  (= :many (-> k key->attribute ::cardinality)))

(>defn to-int [str]
  [string? => int?]
  (if (nil? str)
    0
    (try
      #?(:clj  (Long/parseLong str)
         :cljs (js/parseInt str))
      (catch #?(:clj Exception :cljs :default) e
        0))))

;; TODO: These need to be tied to the database adapter. Native controls in DOM always deal in strings, but
;; it is possible that custom inputs might not need coercion?
(>defn string->value [k v]
  [::qualified-key string? => any?]
  (let [{::keys [type]} (key->attribute k)]
    (case type
      :uuid (new-uuid v)
      :int (to-int v)
      ;; TODO: More coercion
      v)))

(>defn value->string [k v]
  [::qualified-key any? => string?]
  (let [{::keys [type]} (key->attribute k)]
    ;; TODO: more coercion
    (str v)))

(>defn identity?
  [k]
  [qualified-keyword? => boolean?]
  (boolean (some-> k key->attribute ::unique? (= true))))

#?(:clj
   (defn ^String gen-salt []
     (let [sr   (java.security.SecureRandom/getInstance "SHA1PRNG")
           salt (byte-array 16)]
       (.nextBytes sr salt)
       (String. salt))))

#?(:clj
   (defn ^String encrypt
     "Encrypt the given password, returning a string."
     [^String password ^String salt ^Long iterations]
     (let [keyLength           512
           password-characters (.toCharArray password)
           salt-bytes          (.getBytes salt "UTF-8")
           skf                 (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA512")
           spec                (new PBEKeySpec password-characters salt-bytes iterations keyLength)
           key                 (.generateSecret skf spec)
           res                 (.getEncoded key)
           hashed-pw           (.encodeToString (Base64/getEncoder) res)]
       (str salt "|" iterations "|" hashed-pw))))

(>defn attribute?
  [v]
  [any? => boolean?]
  (instance? Attribute v))

(>defn query->eql
  "Convert a query that uses attributes as keys into the proper EQL query. I.e. (eql-query [account/id]) => [::account/id]
   Honors metadata and join nesting."
  [attr-query]
  [vector? => vector?]
  (walk/prewalk
    (fn [ele]
      (if (attribute? ele)
        (::qualified-key ele)
        ele)) attr-query))
