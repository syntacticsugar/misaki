(ns misaki.config
  "Configuration Manager"
  (:use [misaki.util file string sequence]
        [clojure.core.incubator :only [-?> -?>>]]
        [clj-time.core          :only [date-time year month day]]
        [text-decoration.core   :only [cyan red bold]]
        [pretty-error.core      :only [print-pretty-stack-trace]]
        [clostache.parser       :only [render]])
  (:require
    [clojure.string  :as str]
    [clojure.java.io :as io])
  (:import [java.io File FileNotFoundException]))

;; ## Default Value
(def PORT 8080)
(def LANGUAGE "en")
(def COMPILER "default")
(def POST_FILENAME_REGEXP    #"(\d{4})[-_](\d{1,2})[-_](\d{1,2})[-_](.+)$")
(def POST_OUTPUT_NAME_FORMAT "{{year}}/{{month}}/{{filename}}")

(defn bool? [x] (or (true? x) (false? x)))

;; ## Declarations

;; Blog base directory
(def ^{:dynamic true
       :doc "Blog base directory."}
  *base-dir* "")
;; Config filename
(def ^{:dynamic true
       :doc "Config filename.
            Default filename is '_config.clj'."}
  *config-file* "_config.clj")

(declare ^:dynamic *config*)
(declare ^:dynamic *template-dir*)
(declare ^:dynamic *port*)
(declare ^:dynamic *url-base*)
(declare ^:dynamic *index-name*)
(declare ^:dynamic *public-dir*)
(declare ^:dynamic *compiler*)
(declare ^:dynamic *detailed-log*)
(def     ^:dynamic *nolog* false)

;; ## Config Data Wrapper

; =read-config
(defn read-config
  "Load `*config-file*` from `*base-dir*`."
  ([]
   (read-string (slurp (str *base-dir* *config-file*))))
  ([default-value]
   (try (read-config)
        (catch FileNotFoundException e
          default-value))))

; =load-compiler-publics
(defn load-compiler-publics
  "Load specified compiler's public method map."
  [name]
  (let [sym (symbol (str "misaki.compiler." name ".core"))]
    (try
      (require sym)
      (if-let [target-ns (find-ns sym)]
        (ns-publics target-ns)
        (load-compiler-publics "default"))
      (catch FileNotFoundException e
        (load-compiler-publics "default")))))

; =make-basic-config-map
(defn make-basic-config-map
  "Make basic config to pass plugin's configuration."
  []
  (let [config (read-config)]
    (assoc
      config
      :public-dir   (str *base-dir* (:public-dir config))
      :template-dir (str *base-dir* (:template-dir config))
      :port         (:port config PORT)
      :lang         (:lang config LANGUAGE)
      :site         (:site config {})
      :index-name   (:index-name config "")
      :url-base     (normalize-path (:url-base config "/"))
      :detailed-log (:detailed-log config false)
      :compiler     (load-compiler-publics (:compiler config COMPILER)))))

; =with-config
(defmacro with-config
  "Declare config data, and wrap sexp body with them."
  [& body]
  `(let [config# (make-basic-config-map)]
     (binding
       [*template-dir* (:template-dir config#)
        *public-dir*   (:public-dir config#)
        *port*         (:port config#)
        *url-base*     (:url-base config#)
        *index-name*   (:index-name config#)
        *detailed-log* (:detailed-log config#)
        *compiler*     (:compiler config#)]
       ~@body)))

;; ## File Cheker

(defn key->sym [k] (symbol (name k)))

(defn max-arg-num
  [fn-name]
  (let [m  (get *compiler* (key->sym fn-name))
        ls (-> m meta :arglists)]
    (if (some #(find-first (partial = '&) %) ls)
      -1
      (apply max (map count ls)))))


; =config-file?
(defn config-file?
  "Check whether file is config file or not."
  [#^File file]
  {:pre [(file? file)]}
  (= *config-file* (.getName file)))

;; ## Filename Generator

(defn get-index-filename
  "Get index filename string."
  []
  (str *url-base* *index-name*))

; =absolute-path
(defn absolute-path
  "Convert path to absolute with *url-base*"
  [path]
  (if (re-seq #"^https?://" path)
    path
    (let [path (if (.startsWith path "/")
                 (apply str (drop 1 path))
                 path)]
      (str *url-base* path))))

;; ## Compiler config

(defn- compiler-fn-exists? [fn-name]
  (contains? *compiler* (key->sym fn-name)))

; =call-compiler-fn
(defn- call-compiler-fn [fn-name & args]
  (let [fn-sym (key->sym fn-name)
        f      (get *compiler* fn-sym)]
    (if f (apply f args))))

; =get-watch-file-extensions
(defn get-watch-file-extensions
  "Get extensions list to watch."
  []
  (call-compiler-fn :-extension))

; =update-config
(defn update-config
  "Update basic config with plugin's -config function."
  []
  (let [config (make-basic-config-map)
        res    (call-compiler-fn :-config config)]
    (if res res config)))

(defn add-public-dir [filename]
  (str *public-dir*
       (if (.startsWith filename "/")
         (apply str (drop 1 filename))
         filename)))

(defn process-compile-result [result default-filename]
  (cond
    ; output with default filename
    (string? result)
    (if default-filename
      (do (write-file (add-public-dir default-filename) result)
          true)
      false)

    ; only check status
    (or (true? result) (false? result))
    result

    ; output with detailed data
    (map? result)
    (let [{:keys [status   filename body]
           :or   {status   false
                  filename default-filename}} result]
      (if (and filename body)
        (do (write-file (add-public-dir filename) body)
            status)
        status))

    ; error
    :else false))

(defn get-template-files []
  (let [exts (get-watch-file-extensions)]
    (filter
      (fn [file]
        (some #(has-extension? % file) exts))
      (find-files *template-dir*))))

(defn stop-compile? [compile-result]
  (and (map? compile-result)
       (true? (:stop-compile? compile-result))))

; {{{
; =elapsing
(defmacro elapsing
  [& body]
  `(let [start-time# (System/currentTimeMillis)
         ~'get-elapsed-time (fn [] (- (System/currentTimeMillis) start-time#))]
     ~@body))

; =get-result-text
(defn- get-result-text
  [result & optional-string]
  (case result
    true  (cyan (apply str "DONE" optional-string))
    false (red (apply str "FAIL" optional-string))
    (cyan "SKIP")))

; =print-result
(defmacro print-compile-result
  "Print colored compile result."
  [#^String message, compile-sexp]
  `(do
     (println (str " * Compiling " (bold ~message)))
     (flush)
     (elapsing
       (let [result#  ~compile-sexp
             elapsed# (msec->string (~'get-elapsed-time))]
       (println "  " (get-result-text result# " in " elapsed#))))))
; }}}

(defn compile* [config file]
  (try
    (let [compile-result (call-compiler-fn :-compile config file)
          process-result (process-compile-result compile-result (.getName file))]
      [process-result compile-result])
    (catch Exception e
      (print-pretty-stack-trace
        e :filter #(str-contains? (:str %) "misaki"))
      [false {:stop-compile? true}])))

; =compiler-all-compile
(defn compiler-all-compile
  "Call plugin's -compile function for all template files."
  []
  (let [config (update-config)
        files  (get-template-files)]
    (if-not *detailed-log*
      (do (println (str " * Compiling " (bold "all templates"))) (flush)))

    (elapsing
      (let [res (loop [[file & rest-files] files, success? true]
                  (if file
                    (do
                      (when *detailed-log*
                        (println (str " * Compiling " (bold (.getName file))))
                        (flush))
                      (elapsing
                        (let [[process-result compile-result] (compile* config file)
                               result (and success? process-result)]
                          ; compile-result 内にメッセージを指定できるようにする
                          ; => stop する場合にそのメッセージを表示する
                          ;    他に表示するパターンはあってもおｋ
                          ;    or
                          ;    detailed-logを廃止にするとすっきりする。。

                          (if *detailed-log*
                            (println
                              (str "  " (get-result-text process-result)
                                   " in " (msec->string (get-elapsed-time)))))
                          (if (stop-compile? compile-result)
                            result
                            (recur rest-files result))

                          )))
                    success?))]
        (if-not *detailed-log*
          (println
            "  " (get-result-text res)
            " in " (msec->string (get-elapsed-time)))))
      )))

; =compiler-compile
(defn compiler-compile
  "Call plugin's -compile function."
  [file]
  (let [config (update-config)]
    (println (str " * Compiling " (bold (.getName file))))
    (flush)
    (elapsing
      (let [[result] (compile* config file)]
        (println
          "  " (get-result-text result)
          " in " (msec->string (get-elapsed-time)))))))






