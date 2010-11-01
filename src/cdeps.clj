(ns cdeps
  "Does a static analysis of clojure source, specifically the top level
   ns forms in .clj files and generates a dependency graph of namespaces (or packages).
   It will give both java and clojure dependencies assuming Java classes being
   analogous to clojure namespaces. You can also generate an image of the graph generated.
   
   There are broadly two ways of generating such a graph.
   1. One is loading up the entire project in the repl and viewing the dependencies
      at runtime.
   2. Second way is doing a purely static analysis by parsing the top level ns forms
      in the source.
   
   Note that the second method also has the pleasant side effect that one is forced
   to write only one ns form per clj file and that has to be at the top of the file.

   Typical usage would be:
   user=> (def n (cdeps/get-ns-deps \".../some-project/src\"))
   user=> (def p (cdeps/get-package-deps \".../some-project/src\"))
   user=> (cdeps/show-deps-as-image n)
   user=> (cdeps/show-deps-as-image p)

   Documentation is available via autodoc in the autodoc/ directory.

   Note: for images you need dot (http://www.graphviz.org/) added to your system path
   To verify run 'dot -V' on command line.
   See some sample images in the examples/ directory
   "
  (:use
    [clojure.contrib.find-namespaces :only [find-ns-decls-in-dir]]
    [clojure.contrib.prxml :only [prxml]]
    [clojure.contrib.string :only [join]]
    [clojure.pprint :only [pprint]]
    [clojure.java.shell :only [sh]])
  (:import
    [java.io File]))

(defn- libspec?
  "Returns true if x is a libspec"
  [x]
  (or (symbol? x)
    (and (vector? x)
      (or
        (nil? (second x))
        (keyword? (second x))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;; OLD CODE ;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;
;(defmacro with-empty-loaded-libs
;  [& body]
;  `(let [my-ns# (ns-name *ns*)
;         g# (gensym)]
;     (do
;       (in-ns 'clojure.core)
;       (let [s1# (str "(do
;                         (intern *ns* '" g# " @*loaded-libs*)
;                         (dosync (ref-set *loaded-libs* (sorted-set))))")]
;         (eval (read-string s1#)))
;       (in-ns my-ns#)
;       (try
;         ~@body
;         (catch Exception e# (.printStackTrace e#) e#)
;         (finally
;           (in-ns 'clojure.core)
;           (let [s2# (str "(do
;                             (dosync (ref-set *loaded-libs* " g# " ))
;                             (ns-unmap *ns* '" g# "))")]
;             (eval (read-string s2#)))
;           (in-ns my-ns#))))))
;
;(defn extract-libs-from-ns-decl-form-OLD
;  [ns-decl-form]
;  (map (fn [lib] (cond
;                   (class? lib) (.getName lib)
;                   :else (str lib)))
;    (with-empty-loaded-libs
;      (eval ns-decl-form)
;      (doall (concat (vals (ns-imports *ns*))
;               (loaded-libs))))))
;
;(defn extract-libs-from-use-require-args-1
;  [prefix args]
;  (let [args (filter (complement keyword?) args)]
;    (loop [args args libs []]
;      (if-let [arg (first args)]
;        (if (libspec? arg)
;          (let [arg (if (symbol? arg) arg (first arg))
;                arg (if prefix (str prefix \. arg) arg)]
;            (recur (rest args) (conj libs arg)))
;          (let [[prefix1 & args1] arg]
;            (recur (rest args)
;              (vec (concat libs
;                (extract-libs-from-use-require-args-1 prefix1 args1))))))
;        (map str libs)))))
;
;(defn extract-libs-from-ns-decl-form-1
;  [ns-decl-form]
;  (let [ns-decl-form (macroexpand ns-decl-form)
;        with-loading-ctx-form (first
;                                (filter
;                                  (comp #(= % 'clojure.core/with-loading-context) first)
;                                  (filter seq? ns-decl-form)))
;        is-import-form? #(= (first %) 'clojure.core/import)
;        is-use-or-require-form? #(or (= (first %) 'clojure.core/use)
;                                    (= (first %) 'clojure.core/require))
;        [require-use-forms-args import*-forms]
;        (reduce (fn [[r-u i] form]
;                  (cond
;                    (is-use-or-require-form? form) [(conj r-u (map eval (rest form))) i]
;                    (is-import-form? form) [r-u (conj i (macroexpand form))]
;                    :else [r-u i]))
;          [[] []]
;          (filter seq? with-loading-ctx-form))
;        r-u-libs (mapcat #(extract-libs-from-use-require-args-1 nil %) require-use-forms-args)
;        import-libs (mapcat #(map second (rest %)) import*-forms)]
;    (concat r-u-libs import-libs)))
;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;; OLD CODE ENDS ;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;; Private internal stuff ;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- extract-libs-from-use-require-arg
  [prefix arg]
  (if (libspec? arg)
    (let [arg (if (symbol? arg) arg (first arg))
          arg (if prefix (str prefix \. arg) arg)]
      [arg])
    (let [[prefix & args] arg]
      (mapcat #(extract-libs-from-use-require-arg prefix %) args))))

(defn- extract-libs-from-use-require-args
  [args]
  (let [args (filter (complement keyword?) args)]
    (map str (mapcat (partial extract-libs-from-use-require-arg nil) args))))

(defn- extract-libs-from-ns-decl-form
  [ns-decl-form]
  (let [ns-decl-form (macroexpand ns-decl-form)
        with-loading-ctx-form (first
                                (filter
                                  (comp #(= % 'clojure.core/with-loading-context) first)
                                  (filter seq? ns-decl-form)))
        is-import-form? #(= (first %) 'clojure.core/import)
        is-use-or-require-form? #(or (= (first %) 'clojure.core/use)
                                    (= (first %) 'clojure.core/require))
        [require-use-forms-args import*-forms]
        (reduce (fn [[r-u i] form]
                  (cond
                    (is-use-or-require-form? form) [(conj r-u (map eval (rest form))) i]
                    (is-import-form? form) [r-u (conj i (macroexpand form))]
                    :else [r-u i]))
          [[] []]
          (filter seq? with-loading-ctx-form))
        r-u-libs (mapcat extract-libs-from-use-require-args require-use-forms-args)
        import-libs (mapcat #(map second (rest %)) import*-forms)]
    (concat r-u-libs import-libs)))

(defn- extract-ns-from-ns-decl-form
  [ns-decl-form]
  (str (second ns-decl-form)))

(defn- extract-package-from-ns
  [^String ns]
  (if (not (.contains ns "."))
    "default"
    (second (re-find #"(.*)\..*$" ns))))

(defn- extract-packages-from-nses
  [nses]
  (distinct (map extract-package-from-ns nses)))

(defn- filter-libs
  [libs regex-filters]
  (distinct (filter (fn [p] (not (some (fn [r] (re-find r p)) regex-filters))) libs)))

(defn- convert-package-filter-to-regex
  [^String package]
  (let [p (.replaceAll package "\\." "\\\\.")
        p (.replaceAll p "\\*" ".*")]
    (re-pattern (str "^" p))))

(defn- convert-package-filters-to-regexes
  [package-filters]
  (map convert-package-filter-to-regex package-filters))

(defn- extract-filtered-libs-from-ns-decl-form
  [ns-decl-form package-filters]
  (let [regex-package-filters (convert-package-filters-to-regexes package-filters)]
    (filter-libs
      (extract-libs-from-ns-decl-form ns-decl-form)
      regex-package-filters)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;; Outside public Interface ;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-ns-deps
  "Gives a map of ns => dependencies of that ns,
   i.e. a dependecy graph datastructure
   Usage:
   Takes two args (second optional)
   1. Top level directory path of the clojure source
      Parses all .clj files under it
   2. Seq of package filters to exclude from the dependency map
      Like: [\"java.*\" \"javax.*\" \"clojure.*\" \"compojure*\"]
   Functioning:
   Parses all .clj files under the directory and generates the
   dependency graph analyzing only the ns form at the top of each file.
   Generating image:
   See function - show-ns-deps-as-image
   "
  ([^String directory-path package-filters]
    (into {}
      (map #(vec [(extract-ns-from-ns-decl-form %)
                  (extract-filtered-libs-from-ns-decl-form % package-filters)])
        (find-ns-decls-in-dir (File. directory-path)))))
  ([^String directory-path]
    (get-ns-deps directory-path [])))

(defn get-ns-deps-for-one-ns
  "Looks up dependencies of a single ns in ns-deps"
  [ns-deps ^String ns]
  {ns (get ns-deps ns)})

(defn get-ns-deps-for-dependsUpon-one-ns
  "Filters out all namespaces which dependUpon the given ns"
  [ns-deps ^String ns]
  (zipmap
    (filter (comp not nil?)
      (map (fn [[k v]] (when (some #(= ns %) v) k)) ns-deps))
    (map list (repeat ns))))

(defn get-ns-deps-for-one-ns-both-ways
  "Merged output of the 'get-ns-deps-for-one-ns' and
   'get-ns-deps-for-dependsUpon-one-ns' fns"
  [ns-deps ^String ns]
  (merge
    (get-ns-deps-for-one-ns ns-deps ns)
    (get-ns-deps-for-dependsUpon-one-ns ns-deps ns)))

(defn get-ns-deps-for-one-package
  "Looks up dependencies of a single single package in ns-deps"
  [ns-deps ^String package]
  (let [package (str package ".[^.]+$")
        p (convert-package-filter-to-regex package)]
    (into {} (filter (fn [[k v]] (re-matches p k)) ns-deps))))

(defn get-ns-deps-for-dependsUpon-one-package
  "Filters out all namespaces which dependUpon the given package"
  [ns-deps ^String package]
  (let [package (str package ".[^.]+$")
        p (convert-package-filter-to-regex package)]
    (into {}
      (filter (fn [[k v]] (seq v))
        (map (fn [[k v]] [k (filter (comp not nil?)
                              (map #(re-matches p %) v))])
          ns-deps)))))

(defn get-ns-deps-for-one-package-both-ways
  "Merged output of the 'get-ns-deps-for-one-package' and
   'get-ns-deps-for-dependsUpon-one-package' fns"
  [ns-deps ^String package]
  (merge
    (get-ns-deps-for-one-package ns-deps package)
    (get-ns-deps-for-dependsUpon-one-package ns-deps package)))

(defn get-package-deps
  "Same as get-ns-deps but generates a,
   package => dependencies of that package map"
  ([^String directory-path package-filters]
    (apply merge-with (comp distinct concat)
      (map #(hash-map
              (extract-package-from-ns
                (extract-ns-from-ns-decl-form %))
              (extract-packages-from-nses
                (extract-filtered-libs-from-ns-decl-form % package-filters)))
        (find-ns-decls-in-dir (File. directory-path)))))
  ([^String directory-path]
    (get-package-deps directory-path [])))

(defn convert-deps-to-xml
  "Converts deps graph into xml to be consumed by xml2dot.xsl"
  [deps]
  (with-out-str
    (prxml
      [:Something
       [:Classes
        (map (fn [[ns libs]]
               [:Class {:name ns}
                [:DependsUpon (map #(vec [:Class %]) libs)]])
          deps)]])))

(defn gen-xml!
  "Generates a xml file using convert-deps-to-xml"
  [deps xml-file-name]
  (println "Generating xml file" xml-file-name "...")
  (spit xml-file-name (convert-deps-to-xml deps)))

(defn gen-dot!
  "Generates a dot file using xml file generated by gen-xml!"
  [xml-file-name dot-file-name]
  (println "Generating dot file" dot-file-name "...")
  (sh "java" "-cp" (join (System/getProperty "path.separator")
                     ["lib/serializer-2.7.1.jar"
                      "lib/xalan-2.7.1.jar"
                      "lib/xml-apis-1.3.04.jar"])
    "org.apache.xalan.xslt.Process"
    "-IN" xml-file-name
    "-XSL" "xml2dot.xsl"
    "-OUT" dot-file-name))

(defn gen-image!
  "Generates a png image file using the dot file generated by gen-dot!"
  [dot-file-name image-file-name]
  (println "Generating image-file" image-file-name "...")
  (sh "dot" "-T" "png" "-o" image-file-name dot-file-name))

(defn show-image!
  "Just evals (future (sh image-file-name))"
  [image-file-name]
  (future (sh image-file-name)))

(defn- get-tmp-file-name
  ([prefix suffix dir]
    (.getPath
      (doto (File/createTempFile prefix suffix dir)
        (.deleteOnExit))))
  ([prefix suffix]
    (get-tmp-file-name prefix suffix (File. "tmp"))))

(defn show-deps-as-image
  "Given deps generated by any method above (really any graph, i.e. map of
   thing => vector of things), generates an image of the graph.
   The second param opts can have 3 optional params
   1. xml-file-name, 2. dot-file-name, 3. image-file-name
   If any of the above are not given uses a temp file generated in the tmp folder.
   The file is deleted on jvm exit.
   "
  ([deps {:keys [xml-file-name dot-file-name image-file-name] :as opts}]
    (let [xml-file-name (or xml-file-name
                          (get-tmp-file-name "deps" ".xml"))
          dot-file-name (or dot-file-name
                          (get-tmp-file-name "deps" ".dot"))
          image-file-name (or image-file-name
                            (get-tmp-file-name "deps" ".png"))]
      (gen-xml! deps xml-file-name)
      (gen-dot! xml-file-name dot-file-name)
      (gen-image! dot-file-name image-file-name)
      (show-image! image-file-name)
      image-file-name))
  ([deps]
    (show-deps-as-image deps {})))

